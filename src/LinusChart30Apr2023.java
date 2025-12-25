import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Ellipse2D.Double;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;

public class LinusChart30Apr2023 {
	
	public static void main(String[] args) {
		int w = 800;
		int h = 800;
		
		BufferedImage img = new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setPaint(Color.WHITE);
		g.fillRect(0, 0, w,h);
		Ellipse2D.Double circle = new Ellipse2D.Double(0, 0, w, h);
		g.setPaint(Color.BLACK);
		g.draw(circle);
		
		List<Color> colors = Arrays.asList(
				Color.RED,
				Color.YELLOW,
				Color.BLUE,				
				Color.GREEN,
				new Color(255,100,255));
		
		List<String> fingers = Arrays.asList(
				"Thumb","Index","Middle","Ring","Pinky");
		
		
		
		int i = 0;
		double r= w/2;
		
		for (String finger : fingers) {
			for (Color color : colors) {
				double angle = Math.PI*2 * i / 25;				
				Point2D.Double p0 = new Point2D.Double(w/2,h/2);
				{
					double dx = r*Math.cos(angle);
					double dy = r*Math.sin(angle);
					Point2D.Double p1 = new Point2D.Double(w/2+dx,h/2+dy);
					Path2D.Double p2d = new Path2D.Double();
					p2d.moveTo(p0.x,p0.y);
					p2d.lineTo(p1.x,p1.y);
					g.draw(p2d);
				}
				{
					double angle2 = Math.PI*2 * (i+0.5) / 25;
					double r2 = r*0.8;
					double cx = Math.cos(angle2)*r2;
					double cy = Math.sin(angle2)*r2;
					double circleRad = 30;					
					Ellipse2D.Double e = new Ellipse2D.Double(p0.x+cx-circleRad,p0.y+cy-circleRad,circleRad*2,circleRad*2);
					g.setPaint(color);
					g.fill(e);
					
				}
				AffineTransform at0 = g.getTransform();
				AffineTransform at1 = new AffineTransform();
				g.setPaint(Color.BLACK);
				
				g.setFont(g.getFont().deriveFont(30f));
				at1.translate(p0.x, p0.y);
				at1.rotate(angle);
				
				g.setTransform(at1);
				g.drawString(finger, 100,0);
				g.setTransform(at0);
				
				
				i++;
			}
		}
		
		g.dispose();
		BufferedImage img2 = new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
		g = img2.createGraphics();
		g.setPaint(Color.WHITE);
		g.fillRect(0, 0, w, h);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		for (i = 0; i < 10; i++) {
			for (int j = 0; j < 10; j++) {
				Color color = colors.get(i%5);
				g.setPaint(color);;
				Ellipse2D.Double e2d = new Ellipse2D.Double(i*w/10,j*h/10,w/10,h/10);
				g.fill(e2d);
			}		
		}
		
		try {
			ImageIO.write(img, "PNG", new File("C:/Users/catfi/Pictures/mrtwister_spinner.png"));
			ImageIO.write(img2, "PNG", new File("C:/Users/catfi/Pictures/mrtwister_board.png"));
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		JFrame dialog = new JFrame();
		dialog.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		dialog.getContentPane().add(new JLabel(new ImageIcon(img2)),BorderLayout.CENTER);
		dialog.pack();
		dialog.setVisible(true);
		
	}
}
