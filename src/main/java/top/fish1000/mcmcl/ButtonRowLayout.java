package top.fish1000.mcmcl;

/**
 * Shared geometry for bottom action rows: buttons are grouped and centered
 * over the panel instead of spread to the edges, and their widths shrink
 * evenly when the window is too small for the preferred layout.
 */
public final class ButtonRowLayout {
    private ButtonRowLayout() {
    }

    public record Geometry(int[] x, int[] width) {
    }

    /**
     * Computes the bounds of {@code preferredWidths} buttons laid out with
     * {@code gap} pixels between them, centered inside
     * {@code [panelLeft, panelLeft + panelWidth]}.  When the row does not
     * fit, every button shrinks by the same factor so nothing overlaps.
     */
    public static Geometry centered(int panelLeft, int panelWidth, int gap, int... preferredWidths) {
        int count = preferredWidths.length;
        int contentWidth = 0;
        for (int preferredWidth : preferredWidths) {
            contentWidth += preferredWidth;
        }
        int totalGaps = gap * (count - 1);
        int[] widths = new int[count];

        if (contentWidth + totalGaps <= panelWidth) {
            System.arraycopy(preferredWidths, 0, widths, 0, count);
        } else {
            int availableContent = Math.max(count, panelWidth - totalGaps);
            for (int index = 0; index < count; index++) {
                widths[index] = Math.max(1,
                        (int) Math.round((double) preferredWidths[index] * availableContent / contentWidth));
            }
        }

        int total = totalGaps;
        for (int width : widths) {
            total += width;
        }
        int[] x = new int[count];
        int cursor = panelLeft + (panelWidth - total) / 2;
        for (int index = 0; index < count; index++) {
            x[index] = cursor;
            cursor += widths[index] + gap;
        }
        return new Geometry(x, widths);
    }
}
