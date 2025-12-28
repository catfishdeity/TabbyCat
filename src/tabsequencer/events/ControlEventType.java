package tabsequencer.events;

public enum ControlEventType {
	NIL (" ",NilControlEvent.class),
	TIME_SIGNATURE ("T",TimeSignatureEvent.class),
	STICKY_NOTE ("I",StickyNote.class),
	TEMPO ("S",TempoEvent.class);
	
	String token;
	Class<? extends ControlEvent> clazz;
	ControlEventType(String token,Class<? extends ControlEvent> clazz) {
		this.token = token;
		this.clazz = clazz;
	}
}