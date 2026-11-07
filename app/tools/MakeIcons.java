import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;

/** Draws the app icon (192x192) and TV banner (320x180): a fan glyph + title. */
public class MakeIcons {
    public static void main(String[] a) throws Exception {
        File out = new File(a[0]);
        out.mkdirs();
        ImageIO.write(icon(192), "png", new File(out, "icon.png"));
        ImageIO.write(banner(320, 180), "png", new File(out, "banner.png"));
        if (a.length > 1) {                       // README hero image
            File docs = new File(a[1]);
            docs.mkdirs();
            ImageIO.write(hero(1280, 400), "png", new File(docs, "hero.png"));
        }
    }

    static BufferedImage hero(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = g(img);
        g.setPaint(new GradientPaint(0, 0, new Color(0x0E1117), w, h, new Color(0x1E3A8A)));
        g.fill(new RoundRectangle2D.Float(0, 0, w, h, 36, 36));
        g.setPaint(new GradientPaint(0, 0, new Color(0x1E3A8A), 260, 260, new Color(0x0EA5E9)));
        g.fill(new RoundRectangle2D.Float(90, 70, 260, 260, 64, 64));
        fan(g, 220, 200, 86);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 64));
        g.drawString("Performance Keeper", 410, 180);
        g.setColor(new Color(0xC7D2FE));
        g.setFont(new Font("SansSerif", Font.PLAIN, 30));
        g.drawString("Performance mode that turns on — and stays on —", 412, 240);
        g.drawString("for XGIMI projectors", 412, 282);
        g.dispose();
        return img;
    }

    static Graphics2D g(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g;
    }

    static void bg(Graphics2D g, int w, int h, int r) {
        g.setPaint(new GradientPaint(0, 0, new Color(0x1E3A8A), w, h, new Color(0x0EA5E9)));
        g.fill(new RoundRectangle2D.Float(0, 0, w, h, r, r));
    }

    static void fan(Graphics2D g, float cx, float cy, float R) {
        g.setColor(new Color(255, 255, 255, 235));
        for (int i = 0; i < 4; i++) {
            AffineTransform t = g.getTransform();
            g.rotate(Math.toRadians(i * 90 + 20), cx, cy);
            Path2D blade = new Path2D.Float();
            blade.moveTo(cx, cy);
            blade.curveTo(cx + R * 0.15f, cy - R * 0.55f, cx + R * 0.75f, cy - R * 0.95f, cx + R * 0.95f, cy - R * 0.35f);
            blade.curveTo(cx + R * 0.75f, cy - R * 0.15f, cx + R * 0.35f, cy - R * 0.05f, cx, cy);
            g.fill(blade);
            g.setTransform(t);
        }
        g.setColor(new Color(0x1E3A8A));
        g.fill(new Ellipse2D.Float(cx - R * 0.17f, cy - R * 0.17f, R * 0.34f, R * 0.34f));
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(R * 0.07f));
        g.draw(new Ellipse2D.Float(cx - R * 1.08f, cy - R * 1.08f, R * 2.16f, R * 2.16f));
    }

    static BufferedImage icon(int s) {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = g(img);
        bg(g, s, s, s / 4);
        fan(g, s / 2f, s / 2f, s * 0.33f);
        g.dispose();
        return img;
    }

    static BufferedImage banner(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = g(img);
        bg(g, w, h, 24);
        fan(g, 70, h / 2f, 42);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 26));
        g.drawString("Performance", 128, 84);
        g.setFont(new Font("SansSerif", Font.BOLD, 26));
        g.drawString("Keeper", 128, 116);
        g.dispose();
        return img;
    }
}
