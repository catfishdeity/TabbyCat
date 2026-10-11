package tabsequencer.events;

import java.util.Objects;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class TuningChangeEvent extends ControlEvent {
	private final String canvasName;
	private final int row;
	private final double stepOffset;

	public TuningChangeEvent(String canvasName, int row, double stepOffset) {
		super();
		this.canvasName = canvasName;
		this.row = row;
		this.stepOffset = stepOffset;
	}

	public String getCanvasName() { return canvasName; }
	public int getRow()           { return row; }
	public double getStepOffset() { return stepOffset; }

	@Override
	public String toString() {
		if (stepOffset == Math.floor(stepOffset))
			return String.format("%+.0f", stepOffset);
		return String.format("%+.2f", stepOffset);
	}

	@Override
	public ControlEventType getType() {
		return ControlEventType.TUNING_CHANGE;
	}

	@Override
	public Element toXMLElement(Document doc) {
		Element e = doc.createElement("tuningChange");
		e.setAttribute("canvasName", canvasName);
		e.setAttribute("row", "" + row);
		e.setAttribute("offset", "" + stepOffset);
		return e;
	}

	public static TuningChangeEvent fromXMLElement(Element e) {
		return new TuningChangeEvent(
				e.getAttribute("canvasName"),
				Integer.parseInt(e.getAttribute("row")),
				Double.parseDouble(e.getAttribute("offset")));
	}

	@Override
	public int hashCode() {
		return Objects.hash(canvasName, row, stepOffset);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) return true;
		if (obj == null || getClass() != obj.getClass()) return false;
		TuningChangeEvent other = (TuningChangeEvent) obj;
		return row == other.row && Double.compare(stepOffset, other.stepOffset) == 0
				&& Objects.equals(canvasName, other.canvasName);
	}
}
