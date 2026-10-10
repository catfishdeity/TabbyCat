package tabsequencer.config;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class EventCanvasConfig extends CanvasConfig {
	private int numRows;

	public EventCanvasConfig(String name, int numRows) {
		super(name, null, 0, 0);
		this.numRows = numRows;
	}

	@Override public CanvasType getType() { return CanvasType.EVENT; }
	@Override public int getRowCount() { return numRows; }
	@Override public boolean willAccept(String token, int row) { return false; }

	public int getNumRows() { return numRows; }
	public void setNumRows(int n) { this.numRows = n; }

	@Override
	public Element toXMLElement(Document doc, String tagName) {
		Element e = doc.createElement(tagName);
		e.setAttribute("name", name);
		e.setAttribute("numRows", numRows + "");
		if (muted) e.setAttribute("muted", "true");
		return e;
		// Event data is appended by ProjectFileData.toXMLElement
	}

	public static EventCanvasConfig fromXMLElement(Element e) {
		String name = e.getAttribute("name");
		String numRowsAttr = e.getAttribute("numRows");
		int numRows = numRowsAttr.isEmpty() ? 3 : Integer.parseInt(numRowsAttr);
		EventCanvasConfig result = new EventCanvasConfig(name, numRows);
		String mutedAttr = e.getAttribute("muted");
		if (!mutedAttr.isEmpty()) result.muted = Boolean.parseBoolean(mutedAttr);
		return result;
	}
}
