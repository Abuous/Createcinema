package com.yfy.createcinema.poibridge;

import org.apache.poi.common.usermodel.fonts.FontGroup;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFSlideShowImpl;
import org.apache.poi.hslf.usermodel.HSLFTextParagraph;
import org.apache.poi.hslf.usermodel.HSLFTextRun;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextCharacterProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextFont;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

public final class PoiPresentationRenderer {
    private static final String EMBEDDED_CJK_FONT = "/assets/createcinema/fonts/NotoSansCJKsc-Regular.otf";
    private static final String CJK_FONT_FAMILY = loadCjkFontFamily();

    private PoiPresentationRenderer() {
    }

    public static List<Path> render(Path source, Path outputDir, BooleanSupplier cancelled,
                                    BiConsumer<Integer, Integer> progress) throws Exception {
        Files.createDirectories(outputDir);
        String lower = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pptx")) {
            try (XMLSlideShow slideshow = new XMLSlideShow(Files.newInputStream(source))) {
                Dimension pageSize = slideshow.getPageSize();
                List<Path> pages = new ArrayList<>();
                List<XSLFSlide> slides = slideshow.getSlides();
                for (int index = 0; index < slides.size(); index++) {
                    checkCancelled(cancelled);
                    Path output = outputDir.resolve("page-%06d.png".formatted(index));
                    renderXslfSlide(slides.get(index), pageSize, output);
                    pages.add(output);
                    progress.accept(index, slides.size());
                }
                return pages;
            }
        }
        try (HSLFSlideShow slideshow = new HSLFSlideShow(new HSLFSlideShowImpl(source.toString()))) {
            Dimension pageSize = slideshow.getPageSize();
            List<Path> pages = new ArrayList<>();
            List<HSLFSlide> slides = slideshow.getSlides();
            for (int index = 0; index < slides.size(); index++) {
                checkCancelled(cancelled);
                Path output = outputDir.resolve("page-%06d.png".formatted(index));
                renderHslfSlide(slides.get(index), pageSize, output);
                pages.add(output);
                progress.accept(index, slides.size());
            }
            return pages;
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    private static void renderXslfSlide(XSLFSlide slide, Dimension pageSize, Path output) throws IOException {
        BufferedImage image = new BufferedImage(Math.max(1, pageSize.width), Math.max(1, pageSize.height),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            prepareSlideGraphics(graphics, image);
            applyXslfFontFallback(slide);
            slide.draw(graphics);
        } finally {
            graphics.dispose();
        }
        if (!ImageIO.write(image, "png", output.toFile())) throw new IOException("No PNG writer is available");
    }

    private static void renderHslfSlide(HSLFSlide slide, Dimension pageSize, Path output) throws IOException {
        BufferedImage image = new BufferedImage(Math.max(1, pageSize.width), Math.max(1, pageSize.height),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            prepareSlideGraphics(graphics, image);
            applyHslfFontFallback(slide);
            slide.draw(graphics);
        } finally {
            graphics.dispose();
        }
        if (!ImageIO.write(image, "png", output.toFile())) throw new IOException("No PNG writer is available");
    }

    private static void prepareSlideGraphics(Graphics2D graphics, BufferedImage image) {
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    private static void applyXslfFontFallback(XSLFSlide slide) {
        if (CJK_FONT_FAMILY == null) return;
        for (var shape : slide.getShapes()) {
            if (!(shape instanceof XSLFTextShape textShape)) continue;
            for (XSLFTextParagraph paragraph : textShape.getTextParagraphs()) {
                for (XSLFTextRun run : paragraph.getTextRuns()) {
                    if (containsCjk(run.getRawText())) applyXslfRunFallback(run);
                }
            }
        }
    }

    private static void applyXslfRunFallback(XSLFTextRun run) {
        CTTextCharacterProperties properties = run.getRPr(true);
        setXslfFontFamily(properties.isSetEa() ? properties.getEa() : properties.addNewEa());
        setXslfFontFamily(properties.isSetLatin() ? properties.getLatin() : properties.addNewLatin());
        setXslfFontFamily(properties.isSetCs() ? properties.getCs() : properties.addNewCs());
    }

    private static void setXslfFontFamily(CTTextFont font) {
        font.setTypeface(CJK_FONT_FAMILY);
    }

    private static void applyHslfFontFallback(HSLFSlide slide) {
        if (CJK_FONT_FAMILY == null) return;
        for (var shape : slide.getShapes()) {
            if (!(shape instanceof HSLFTextShape textShape)) continue;
            for (HSLFTextParagraph paragraph : textShape.getTextParagraphs()) {
                for (HSLFTextRun run : paragraph.getTextRuns()) {
                    if (containsCjk(run.getRawText())) {
                        run.setFontFamily(CJK_FONT_FAMILY, FontGroup.EAST_ASIAN);
                        run.setFontFamily(CJK_FONT_FAMILY, FontGroup.LATIN);
                    }
                }
            }
        }
    }

    private static boolean containsCjk(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            if ((codePoint >= 0x2E80 && codePoint <= 0x9FFF)
                    || (codePoint >= 0xF900 && codePoint <= 0xFAFF)) return true;
            index += Character.charCount(codePoint);
        }
        return false;
    }

    private static String loadCjkFontFamily() {
        String embedded = registerEmbeddedCjkFont();
        return embedded != null ? embedded : findInstalledCjkFontFamily();
    }

    private static String registerEmbeddedCjkFont() {
        try (InputStream stream = PoiPresentationRenderer.class.getResourceAsStream(EMBEDDED_CJK_FONT)) {
            if (stream == null) return null;
            Font font = Font.createFont(Font.TRUETYPE_FONT, stream);
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
            return font.getFamily();
        } catch (FontFormatException | IOException error) {
            return null;
        }
    }

    private static String findInstalledCjkFontFamily() {
        String[] available = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        List<String> candidates = List.of("Microsoft YaHei", "Microsoft YaHei UI", "SimSun", "NSimSun",
                "Noto Sans CJK SC", "Noto Sans CJK", "Source Han Sans CN", "WenQuanYi Zen Hei",
                "Arial Unicode MS", "Unifont");
        for (String candidate : candidates) {
            for (String family : available) {
                if (candidate.equalsIgnoreCase(family)) return family;
            }
        }
        return null;
    }
}
