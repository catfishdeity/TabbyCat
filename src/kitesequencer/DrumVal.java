package kitesequencer;

import java.util.Optional;

enum DrumVal {
	
	KICK ("K",36,DrumScorePosition.FEET),
	PEDAL_HAT ("F",44,DrumScorePosition.FEET),
	SNARE ("S",38,DrumScorePosition.HANDS),
	CLOSED_HAT ("X",42,DrumScorePosition.HANDS),
	OPEN_HAT ("O",46,DrumScorePosition.HANDS),
	CRASH ("C",49,DrumScorePosition.HANDS),
	RIDE ("R",51,DrumScorePosition.HANDS),
	HI_TOM ("H",48,DrumScorePosition.HANDS),
	MID_TOM ("M",47,DrumScorePosition.HANDS),
	LOW_TOM ("L",45,DrumScorePosition.HANDS)	
	;
	
	String token;
	DrumScorePosition scorePosition;
	int midiNote;
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
	
	public int getMidiNote() {
		return midiNote;
	}
	DrumVal(String token, int midiNote, DrumScorePosition position) {
		this.token = token;
		this.midiNote = midiNote;
		this.scorePosition = position;
	}
	
	
}