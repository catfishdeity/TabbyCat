package kitesequencer.events;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class NilControlEvent extends ControlEvent {

	private static final NilControlEvent instance = new NilControlEvent();
	
	public static NilControlEvent get() {
		return instance;
	}
	
	@Override
	public String toString() {
		return " ";
	}
	@Override
	public ControlEventType getType() {
		return ControlEventType.NIL;
	}

	@Override
	public Element toXMLElement(Document doc) {
		return doc.createElement("nilEvent");		
	}
	
}