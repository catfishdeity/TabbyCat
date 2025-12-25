package kitesequencer.events;

public enum TimeSignatureDenominator {
	_2, _4, _8, _16;
	
	@Override
	public String toString() {
		return super.toString().replace("_","");
	}
	
	static TimeSignatureDenominator lookup(String token) {
		for (TimeSignatureDenominator a : values()) {
			if (a.toString() == token) {
				return a;
			}			
		}
		return _4;
	}

	public int getValue() {
		return Integer.parseInt(toString());
	}
}