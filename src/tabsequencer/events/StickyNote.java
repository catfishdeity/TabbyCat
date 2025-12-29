package tabsequencer.events;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class StickyNote extends ControlEvent {
	
	private final String text;
	
	public StickyNote(String text) {
		this.text = text;
	
	}

	
	public String getText() {
		return text;
	}
	@Override
	public ControlEventType getType() {
		return ControlEventType.STICKY_NOTE;
	}
	
	@Override
	public String toString() {
		return getText();
	}

	@Override
	public Element toXMLElement(Document doc) {
		Element e = doc.createElement("note");
		e.setTextContent(this.getText());
		return e;
	}
	
	public static StickyNote fromXMLElement(Element e) {

		return new StickyNote(e.getTextContent());
	}

}
