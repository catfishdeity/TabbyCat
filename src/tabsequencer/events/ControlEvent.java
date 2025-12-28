package tabsequencer.events;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

public abstract class ControlEvent {
	public abstract ControlEventType getType();
	public abstract Element toXMLElement(Document doc);
		
}