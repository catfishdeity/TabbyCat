package tabsequencer.config;

import java.util.Objects;

import org.w3c.dom.Element;

public class PercToken {
	
	public static PercToken fromXMLElement(Element e) {
		return new PercToken(
				e.getAttribute("token"),
				PercRowType.lookup(e.getAttribute("position")),
				Integer.parseInt(e.getAttribute("midiNumber")));
	}
	private final String token;
	private final PercRowType position;
	private final int midiNumber;
	public PercToken(String token, PercRowType position, int midiNumber) {
		this.token = token;
		this.position = position;
		this.midiNumber = midiNumber;
	}
	@Override
	public int hashCode() {
		return Objects.hash(midiNumber, position, token);
	}
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		PercToken other = (PercToken) obj;
		return midiNumber == other.midiNumber && position == other.position && Objects.equals(token, other.token);
	}
	@Override
	public String toString() {
		return "PercRowToken [token=" + token + ", position=" + position + ", midiNumber=" + midiNumber + "]";
	}
	public String getToken() {
		return token;
	}
	public PercRowType getPosition() {
		return position;
	}
	public int getMidiNumber() {
		return midiNumber;
	}
}