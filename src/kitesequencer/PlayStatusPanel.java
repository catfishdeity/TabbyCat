package kitesequencer;

import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.JPanel;

class PlayStatusPanel extends JPanel {
	private PlayStatus playStatus = PlayStatus.STOP;
	
	public void setPlayStatus(PlayStatus status) {
		this.playStatus = status;
	}
	
	public PlayStatus getPlayStatus() {
		return playStatus;
	}
	
	@Override
	public void paint(Graphics g_) {
		Graphics2D g = (Graphics2D) g_;
		g.setPaint(this.playStatus.color);
	
		g.fillRoundRect(0, 0, getWidth(), getHeight(),10,10);
	}
}