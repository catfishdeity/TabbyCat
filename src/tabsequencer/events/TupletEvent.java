package tabsequencer.events;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class TupletEvent extends ControlEvent {

	private final int subdivisions;
	private final int endT; // exclusive end of the range

	public TupletEvent(int subdivisions, int endT) {
		this.subdivisions = subdivisions;
		this.endT = endT;
	}

	public int getSubdivisions() { return subdivisions; }
	public int getEndT()         { return endT; }

	@Override
	public ControlEventType getType() {
		return ControlEventType.TUPLET;
	}

	@Override
	public String toString() {
		return subdivisions + "tup";
	}

	@Override
	public Element toXMLElement(Document doc) {
		Element e = doc.createElement("tuplet");
		e.setAttribute("n", subdivisions + "");
		e.setAttribute("end", endT + "");
		return e;
	}

	public static TupletEvent fromXMLElement(Element e) {
		return new TupletEvent(
			Integer.parseInt(e.getAttribute("n")),
			Integer.parseInt(e.getAttribute("end")));
	}
}
