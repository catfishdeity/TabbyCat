package tabsequencer.events;

public enum ControlEventType {
	
	TIME_SIGNATURE ("T",TimeSignatureEvent.class),
	STICKY_NOTE ("I",StickyNote.class),
	TEMPO ("S",TempoEvent.class),
	PROGRAM_CHANGE ("B",ProgramChange.class);
	
	String token;
	Class<? extends ControlEvent> clazz;
	ControlEventType(String token,Class<? extends ControlEvent> clazz) {
		this.token = token;
		this.clazz = clazz;
	}
}