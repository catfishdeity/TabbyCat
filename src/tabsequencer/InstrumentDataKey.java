package tabsequencer;

import java.util.Objects;

public class InstrumentDataKey {
	private final String instrumentId;
	private final int time,row;

	public InstrumentDataKey(String instrumentId, int time, int row) {
		this.instrumentId = instrumentId;
		this.time = time;
		this.row = row;
	}

	public String getInstrumentId() {
		return instrumentId;
	}

	/** @deprecated Use {@link #getInstrumentId()} */
	public String getInstrumentName() {
		return instrumentId;
	}

	public int getTime() {
		return time;
	}

	public int getRow() {
		return row;
	}

	@Override
	public int hashCode() {
		return Objects.hash(instrumentId, row, time);
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
		return Objects.equals(instrumentId, other.instrumentId) && row == other.row && time == other.time;
	}

	@Override
	public String toString() {
		return "InstrumentDataKey [instrumentId=" + instrumentId + ", time=" + time + ", row=" + row + "]";
	}
}
