package tabsequencer.events;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class ShuffleEvent extends ControlEvent {

	private final int shuffle;

	public ShuffleEvent(int shuffle) {
		this.shuffle = shuffle;
	}

	public int getShuffle() { return shuffle; }

	@Override
	public ControlEventType getType() { return ControlEventType.SHUFFLE; }

	@Override
	public String toString() { return shuffle + "shuf"; }

	@Override
	public Element toXMLElement(Document doc) {
		Element e = doc.createElement("shuffle");
		e.setAttribute("v", shuffle + "");
		return e;
	}

	public static ShuffleEvent fromXMLElement(Element e) {
		return new ShuffleEvent(Integer.parseInt(e.getAttribute("v")));
	}
}
