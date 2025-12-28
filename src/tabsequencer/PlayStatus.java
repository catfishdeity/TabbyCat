package tabsequencer;

import java.awt.Color;

enum PlayStatus {
	PLAY (Color.GREEN), STOP(Color.RED);
	
	public final Color color;
	
	PlayStatus(Color color){
		this.color = color;
	}
}