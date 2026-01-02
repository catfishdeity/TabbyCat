package tabsequencer;

import java.util.Objects;

public class InstrumentDataKey {
	private final String instrumentName;
	private final int time,row;
	
	public InstrumentDataKey(String instrumentName, int time, int row) {
		this.instrumentName = instrumentName;
		this.time = time;
		this.row = row;
	}

	public String getInstrumentName() {
		return instrumentName;
	}

	public int getTime() {
		return time;
	}

	public int getRow() {
		return row;
	}

	@Override
	public int hashCode() {
		return Objects.hash(instrumentName, row, time);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		InstrumentDataKey other = (InstrumentDataKey) obj;
		return Objects.equals(instrumentName, other.instrumentName) && row == other.row && time == other.time;
	}

	@Override
	public String toString() {
		return "InstrumentDataKey [instrumentName=" + instrumentName + ", time=" + time + ", row=" + row + "]";
	}


	
}