package kitesequencer.events;

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
	public Element toXMLElement(Document doc) {
		Element e = doc.createElement("note");
		//e.setAttribute("r", color.getRed()+"");
		//e.setAttribute("g", color.getGreen()+"");
		//e.setAttribute("b", color.getBlue()+"");
		//e.setAttribute("a", color.getAlpha()+"");
		e.setTextContent(this.getText());
		return e;
	}
	
	public static StickyNote fromXMLElement(Element e) {
		//Color color = new Color(
			//	Integer.parseInt(e.getAttribute("r")),
				//Integer.parseInt(e.getAttribute("g")),
				//Integer.parseInt(e.getAttribute("b")),
				//Integer.parseInt(e.getAttribute("a")));
		return new StickyNote(e.getTextContent());
	}

}
