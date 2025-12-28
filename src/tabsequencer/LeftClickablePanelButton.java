package tabsequencer;

import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;

import javax.swing.JPanel;

public class LeftClickablePanelButton extends JPanel implements MouseListener {
	
	private final Runnable r;
	public LeftClickablePanelButton(Runnable r) {
		this.addMouseListener(this);
		this.r = r;
	}

	@Override
	public void mouseClicked(MouseEvent e) {
		if (e.getButton()!=MouseEvent.BUTTON1) {
			return;
		}
		r.run();		
	}

	@Override
	public void mousePressed(MouseEvent e) {}

	@Override
	public void mouseReleased(MouseEvent e) {}

	@Override
	public void mouseEntered(MouseEvent e) {}
	
	@Override
	public void mouseExited(MouseEvent e) {}

}
