package kitesequencer;

import java.util.Optional;

enum DrumVal {
	
	KICK ("K",DrumScorePosition.FEET),
	PEDAL_HAT ("F",DrumScorePosition.FEET),
	SNARE ("S",DrumScorePosition.HANDS),
	CLOSED_HAT ("X",DrumScorePosition.HANDS),
	OPEN_HAT ("O",DrumScorePosition.HANDS),
	CRASH ("C",DrumScorePosition.HANDS),
	RIDE ("R",DrumScorePosition.HANDS),
	HI_TOM ("H",DrumScorePosition.HANDS),
	MID_TOM ("M",DrumScorePosition.HANDS),
	LOW_TOM ("L",DrumScorePosition.HANDS),	
	NIL(" ",DrumScorePosition.NIL);
	
	String token;
	DrumScorePosition scorePosition;
	
	public DrumScorePosition getScorePosition() {
		return scorePosition;
	}
	
	public String getToken() {
		return token;
	}
	
	public static Optional<DrumVal> lookup(String token) {
		for (DrumVal a : values() ) {
			if (a.getToken().equals(token.toUpperCase())) {
				return Optional.of(a);
			}
		}
		return Optional.empty();
	}
	
	DrumVal(String token, DrumScorePosition position) {
		this.token = token;
		this.scorePosition = position;
	}
	
	
}