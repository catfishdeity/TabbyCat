package tabsequencer.events;

import java.util.Objects;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class ProgramChange extends ControlEvent {
	private final String canvasName;
	private final int bank;
	private final int program;
	
	
	public ProgramChange(String canvasName, int bank, int program) {
		super();
		this.canvasName = canvasName;
		this.bank = bank;
		this.program = program;
	}
	
	
	public String getInstrument() {
		return canvasName;
	}


	public int getBank() {
		return bank;
	}


	public int getProgram() {
		return program;
	}
	
	@Override
	public String toString() {
		return String.format("%s -> (%d, %d)", canvasName,bank,program);
	}


	@Override
	public int hashCode() {
		return Objects.hash(bank, canvasName, program);
	}
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ProgramChange other = (ProgramChange) obj;
		return bank == other.bank && Objects.equals(canvasName, other.canvasName) && program == other.program;
	}


	@Override
	public ControlEventType getType() {

		return ControlEventType.PROGRAM_CHANGE;
	}


	@Override
	public Element toXMLElement(Document doc) {
		Element e = doc.createElement("programChange");
		e.setAttribute("canvasName",canvasName);
		e.setAttribute("bank",""+bank);
		e.setAttribute("program",""+program);
		return e;
	}
	
	public static ProgramChange fromXMLElement(Element e) {
		return new ProgramChange(
				e.getAttribute("canvasName"),
				Integer.parseInt(e.getAttribute("bank")),
				Integer.parseInt(e.getAttribute("program")));
	}
	
}
