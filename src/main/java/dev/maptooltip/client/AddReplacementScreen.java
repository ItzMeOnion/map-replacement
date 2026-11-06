package dev.maptooltip.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Create or edit one "map ID -> image" replacement, with a live preview. */
public class AddReplacementScreen extends Screen {
    private static final int PANEL_W = 480;
    private static final int PANEL_H = 300;
    private static final int PREVIEW_CELLS = 32;
    private static final int PREVIEW_PX = 4;

    private final Screen parent;
    private final Replacement editing;

    private TextFieldWidget mapIdField;
    private TextFieldWidget nameField;
    private TextFieldWidget pathField;

    private byte[] previewColors;
    private boolean busy = false;
    private String status = "";
    private int statusColor = Ui.TEXT_DIM;

    public AddReplacementScreen(Screen parent, Replacement editing) {
        super(Text.literal(editing == null ? "New Replacement" : "Edit Replacement"));
        this.parent = parent;
        this.editing = editing;
    }

    private int top() { return Math.max(10, height / 2 - PANEL_H / 2); }
    private int left() { return (width - Math.min(width - 24, PANEL_W)) / 2; }
    private int panelW() { return Math.min(width - 24, PANEL_W); }

    @Override
    protected void init() {
        int left = left(), top = top();

        mapIdField = new TextFieldWidget(textRenderer, left + 16, top + 66, 230, 20, Text.literal("Map ID"));
        mapIdField.setMaxLength(10);
        mapIdField.setTextPredicate(s -> s.matches("\\d{0,10}"));
        mapIdField.setPlaceholder(Text.literal("e.g. 1452").formatted(Formatting.DARK_GRAY));
        addDrawableChild(mapIdField);

        nameField = new TextFieldWidget(textRenderer, left + 16, top + 108, 230, 20, Text.literal("Name"));
        nameField.setMaxLength(32);
        nameField.setPlaceholder(Text.literal("Optional label").formatted(Formatting.DARK_GRAY));
        addDrawableChild(nameField);

        pathField = new TextFieldWidget(textRenderer, left + 16, top + 150, 230, 20, Text.literal("Image"));
        pathField.setMaxLength(1024);
        pathField.setPlaceholder(Text.literal("Browse or paste a path").formatted(Formatting.DARK_GRAY));
        pathField.setChangedListener(s -> previewColors = null);
        addDrawableChild(pathField);

        addDrawableChild(ButtonWidget.builder(Text.literal("Browse"), b -> browse())
                .dimensions(left + 250, top + 150, 64, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Preview"), b -> preview())
                .dimensions(left + 16, top + 182, 120, 20).build());

        addDrawableChild(ButtonWidget.builder(
                        Text.literal(editing == null ? "Save replacement" : "Save changes").formatted(Formatting.BOLD),
                        b -> save())
                .dimensions(left + 16, top + 220, 218, 24).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> close())
                .dimensions(left + 246, top + 220, 218, 24).build());

        if (editing != null) {
            mapIdField.setText(String.valueOf(editing.mapId()));
            nameField.setText(editing.name());
            pathField.setText(editing.sourcePath());
            previewColors = ReplacementManager.get().imageFor(editing.id());
        }
    }

    // ---- actions --------------------------------------------------------------

    private void browse() {
        try {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Choose an image for your map");
            chooser.setFileFilter(new FileNameExtensionFilter("Images (png, jpg, gif, bmp)", "png", "jpg", "jpeg", "gif", "bmp"));
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                pathField.setText(chooser.getSelectedFile().getAbsolutePath());
                previewColors = null;
                setStatus("Image selected, press Preview to see it", Ui.TEXT_DIM);
            }
        } catch (RuntimeException e) {
            setStatus("Could not open the file picker, paste the path instead", Ui.RED);
        }
    }

    private void preview() {
        Path path = parsePath();
        if (path == null) return;
        try {
            previewColors = ImageConverter.convert(path);
            setStatus("Looks good, this is how it will appear on the map", Ui.GREEN);
        } catch (IOException e) {
            setStatus(e.getMessage(), Ui.RED);
        }
    }

    private void save() {
        if (busy) return;

        String idText = mapIdField.getText().trim();
        if (idText.isEmpty()) {
            setStatus("Enter the map ID you want to replace", Ui.RED);
            return;
        }
        int mapId;
        try {
            mapId = Integer.parseInt(idText);
        } catch (NumberFormatException e) {
            setStatus("Map ID must be a whole number", Ui.RED);
            return;
        }
        if (mapId < 0) {
            setStatus("Map ID can't be negative", Ui.RED);
            return;
        }

        final String name = nameField.getText().trim().isEmpty()
                ? "Map #" + mapId
                : nameField.getText().trim();

        String rawPath = cleanPath(pathField.getText());
        boolean keepExistingImage = editing != null && rawPath.equals(editing.sourcePath())
                && previewColors != null;

        if (keepExistingImage) {
            finish(mapId, name, rawPath, previewColors);
            return;
        }

        final Path path = parsePath();
        if (path == null) return;

        busy = true;
        setStatus("Converting image...", Ui.TEXT_DIM);
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return ImageConverter.convert(path);
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                })
                .whenComplete((colors, error) -> client.execute(() -> {
                    busy = false;
                    if (error != null) {
                        Throwable cause = error instanceof CompletionException && error.getCause() != null
                                ? error.getCause() : error;
                        setStatus(cause.getMessage() != null ? cause.getMessage() : "Conversion failed", Ui.RED);
                        return;
                    }
                    finish(mapId, name, path.toString(), colors);
                }));
    }

    private void finish(int mapId, String name, String sourcePath, byte[] colors) {
        ReplacementManager mgr = ReplacementManager.get();
        String id = editing != null ? editing.id() : ReplacementManager.newId();
        boolean enabled = editing == null || editing.enabled();
        try {
            mgr.upsert(new Replacement(id, mapId, name, sourcePath, enabled), colors);
        } catch (IOException e) {
            setStatus("Could not save: " + e.getMessage(), Ui.RED);
            return;
        }
        client.setScreen(parent);
    }

    private Path parsePath() {
        String raw = cleanPath(pathField.getText());
        if (raw.isEmpty()) {
            setStatus("Choose an image file first", Ui.RED);
            return null;
        }
        try {
            Path path = Path.of(raw);
            if (!Files.isRegularFile(path)) {
                setStatus("That file doesn't exist", Ui.RED);
                return null;
            }
            return path;
        } catch (InvalidPathException e) {
            setStatus("That path isn't valid on this system", Ui.RED);
            return null;
        }
    }

    /** Strips the quotes Windows adds when you "Copy as path". */
    private static String cleanPath(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }

    private void setStatus(String text, int color) {
        this.status = text;
        this.statusColor = color;
    }

    // ---- rendering ------------------------------------------------------------

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        Ui.background(ctx, width, height);

        int left = left(), top = top(), pw = panelW();
        Ui.panel(ctx, left, top, pw, PANEL_H);

        ctx.drawTextWithShadow(textRenderer,
                Text.literal(editing == null ? "NEW REPLACEMENT" : "EDIT REPLACEMENT").formatted(Formatting.BOLD),
                left + 16, top + 12, Ui.GOLD);
        ctx.drawTextWithShadow(textRenderer, "Pick a map ID and any picture", left + 16, top + 26, Ui.TEXT_DIM);

        ctx.drawTextWithShadow(textRenderer, "MAP ID", left + 16, top + 54, Ui.GOLD);
        ctx.drawTextWithShadow(textRenderer, "NAME", left + 16, top + 96, Ui.GOLD);
        ctx.drawTextWithShadow(textRenderer, "IMAGE FILE", left + 16, top + 138, Ui.GOLD);

        // preview frame
        int px = left + 326, py = top + 52, size = PREVIEW_CELLS * PREVIEW_PX;
        ctx.fill(px - 3, py - 3, px + size + 3, py + size + 3, Ui.GOLD_DIM);
        if (previewColors != null) {
            Ui.mosaic(ctx, previewColors, px, py, PREVIEW_CELLS, PREVIEW_PX);
        } else {
            Ui.checker(ctx, px, py, size, size, 8);
            ctx.drawCenteredTextWithShadow(textRenderer, "No preview yet", px + size / 2, py + size / 2 - 4, Ui.TEXT_DIM);
        }
        ctx.drawCenteredTextWithShadow(textRenderer, "PREVIEW  (128 x 128)", px + size / 2, top + 38, Ui.GOLD);

        ctx.drawCenteredTextWithShadow(textRenderer,
                "Cropped to a square, scaled to 128x128 and dithered to the map palette.",
                left + pw / 2, top + 254, Ui.TEXT_DIM);
        ctx.drawCenteredTextWithShadow(textRenderer,
                "Tip: go to the map on Donut SMP, read its ID, then paste it above.",
                left + pw / 2, top + 266, Ui.TEXT_DIM);
        ctx.drawCenteredTextWithShadow(textRenderer, status, left + pw / 2, top + 280, statusColor);

        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }
}
