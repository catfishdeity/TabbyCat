import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

public class CreateSheetMusic {
	public static void main(String[] args) throws IOException {
		BufferedImage img = new BufferedImage(1100,850,BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setPaint(Color.WHITE);
		g.fillRect(0,0,1100,850);
		g.setPaint(Color.black);
		g.drawLine(5,20,300,20);
		g.drawLine(400, 20, 1095,20);
		
		int spacing = 20;
		int rowHeight = 8;
		int groupHeight = 800/3;
		int y0 = 50;
		for (int a = 0; a < 3; a++) {
			int y = y0+a*groupHeight;
			if (a != 0) {
				//g.setColor(new Color(100,150,200));
				g.setStroke(new BasicStroke(1,BasicStroke.CAP_BUTT,BasicStroke.JOIN_BEVEL,1.0f,new float[] {2,7},0f));
				g.drawLine(50, y-spacing/2-5,1050,y-spacing/2-5);
			}
			g.setColor(Color.BLACK);
			//g.setStroke(new BasicStroke(3));
			//g.drawLine(10,y,1095,y);
			g.setStroke(new BasicStroke(1));
			g.setFont(new Font("Monospaced",Font.ITALIC,12));
			y+=5;
			g.drawString("Guitar",5,y-2);
			for (int i = 0; i < 7; i++) {
				g.drawLine(5, y, 1095, y);
				y+=rowHeight;
			}
			y+=spacing;
			g.drawString("Bass",5,y-2);
			for (int i = 0; i < 6; i++) {
				g.drawLine(5, y, 1095, y);;
				y+=rowHeight;
			}
			y+=spacing;
			g.drawString("Drums",5,y-2);
			for (int i = 0; i < 4; i++) {
				g.drawLine(5, y, 1095, y);;
				y+=rowHeight;
			}
			y+=spacing;
			g.drawString("Vocals",5,y-2);
			for (int i = 0; i < 5; i++) {
				g.drawLine(5, y, 1095, y);;
				y+=rowHeight;
			}
			
			
		}
		//JOptionPane.showInputDialog(new ImageIcon(img));
		ImageIO.write(img, "png", new File("sheetMusic.png"));
	}	
}
