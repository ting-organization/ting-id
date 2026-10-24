// Puts a phone screenshot in a plain phone frame for the README: the screen
// with rounded corners in a dark bezel with a lighter edge, so it reads on
// light and dark pages alike, on a transparent background, at half size.
//
//   java scripts/FrameScreenshot.java <screenshot.png> <framed.png>

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class FrameScreenshot {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: java scripts/FrameScreenshot.java <screenshot.png> <framed.png>");
            System.exit(1);
        }
        BufferedImage shot = ImageIO.read(new File(args[0]));
        int screenWidth = Math.round(shot.getWidth() * 0.5f);
        int screenHeight = Math.round(shot.getHeight() * 0.5f);
        int bezel = 12;
        int width = screenWidth + 2 * bezel;
        int height = screenHeight + 2 * bezel;
        // Phone screens round their corners by about a tenth of their width.
        float screenRadius = screenWidth * 0.09f;
        float outerRadius = screenRadius + bezel;

        // The screen at half size, averaged rather than sampled, with its corners rounded by an antialiased mask.
        BufferedImage screen = new BufferedImage(screenWidth, screenHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D s = smooth(screen.createGraphics());
        s.setColor(Color.WHITE);
        s.fill(new RoundRectangle2D.Float(0, 0, screenWidth, screenHeight, 2 * screenRadius, 2 * screenRadius));
        s.setComposite(AlphaComposite.SrcIn);
        s.drawImage(shot.getScaledInstance(screenWidth, screenHeight, Image.SCALE_AREA_AVERAGING), 0, 0, null);
        s.dispose();

        BufferedImage framed = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(framed.createGraphics());
        g.setColor(new Color(18, 23, 33));
        g.fill(new RoundRectangle2D.Float(0, 0, width, height, 2 * outerRadius, 2 * outerRadius));
        g.setColor(new Color(69, 79, 97));
        g.setStroke(new BasicStroke(2f));
        g.draw(new RoundRectangle2D.Float(1, 1, width - 2, height - 2, 2 * (outerRadius - 1), 2 * (outerRadius - 1)));
        g.drawImage(screen, bezel, bezel, null);
        g.dispose();
        ImageIO.write(framed, "png", new File(args[1]));
    }

    private static Graphics2D smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        return g;
    }
}
