package tabsequencer.events;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class TimeSignatureEvent extends ControlEvent {
	
	
	public int numerator;
	public TimeSignatureDenominator denominator;
	public TimeSignatureEvent(int numerator, TimeSignatureDenominator denominator) {
		
		this.numerator = numerator;
		this.denominator = denominator;
	}
	
	public int get16ths() {
		switch (denominator) {
		case _16:
			return numerator;			
		case _2:
			return numerator*8;	
		case _4:
			return numerator*4;			
		case _8:
			return numerator*2;
		}
		 
		return numerator*4;
	}
	
	public String toString() {
		return String.format("%d/%d",numerator,denominator.getValue());
	}

	@Override
	public ControlEventType getType() {		
		return ControlEventType.TIME_SIGNATURE;
	}

	@Override
	public Element toXMLElement(Document doc) {
		Element e = doc.createElement("timeSignature");
		e.setAttribute("n",""+numerator);
		e.setAttribute("d", denominator.getValue()+"");
		return e;
	}
	
	public static TimeSignatureEvent fromXMLElement(Element e) {
		return new TimeSignatureEvent(
				Integer.parseInt(e.getAttribute("n")),
				TimeSignatureDenominator.fromInt(Integer.parseInt(e.getAttribute("d")))
				.orElse(TimeSignatureDenominator._4));
	}


}