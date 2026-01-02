package tabsequencer.events;

import java.util.Optional;

public enum TimeSignatureDenominator {
	_2, _4, _8, _16;
	
	@Override
	public String toString() {
		return super.toString().replace("_","");
	}
	
	public static Optional<TimeSignatureDenominator> fromInt(int i) {
		switch (i) {
		case 2: return Optional.of(_2);
		case 4: return Optional.of(_4);
		case 8: return Optional.of(_8);
		case 16: return Optional.of(_16);
		default:
			return Optional.empty();
		}
		
	}
	

	public int getValue() {
		return Integer.parseInt(toString());
	}
}