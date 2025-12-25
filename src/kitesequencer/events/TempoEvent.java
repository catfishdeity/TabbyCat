package kitesequencer.events;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class TempoEvent extends ControlEvent {

	@Override
	public ControlEventType getType() {		
		return ControlEventType.TEMPO;
	}
	
	@Override
	public String toString() {
		return getTempo()+"bpm";
	}
	
	private final int tempo;
	public TempoEvent(int tempo) {
		this.tempo = tempo;
	}

	@Override
	public Element toXMLElement(Document doc) {
		Element element = doc.createElement("tempo");
		element.setAttribute("v", getTempo()+"");
		return element;
	}
	
	public static TempoEvent fromXMLElement(Element e) {
		return new TempoEvent(Integer.parseInt(e.getAttribute("v"))); 
	}

	public int getTempo() {
		return tempo;
	}
	
	
}