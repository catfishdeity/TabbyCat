package kitesequencer;

import java.util.Optional;
import java.util.OptionalInt;

enum InputVal {
	_0(0),
	_1(2),
	_2(4),
	_3(6),
	_4(8),
	_5(10),
	_6(12),
	_7(14),
	_8(16),
	_9(18),
	_10(20),
	_11(22),
	_12(24),
	_13(26),
	_14(28),
	_15(30),
	_16(32),
	_17(34),
	_18(36),
	_19(38),
	_20(40),
	_21(42),
	_22(44),
	_23(46),
	_24(48),
	_25(50),
	_26(52),
	_27(54),
	_28(56),
	_29(58),
	_30(60),
	_31(62),
	_32(64),
	_33(66),
	_34(68),
	_35(70),
	_36(72),
	_37(74),
	_38(76),
	_39(78),
	_40(80),
	_41(82),
	_b(3,"B"),
	_d(7,"D"),
	_e(9,"E"),
	NIL (" "),
	HOLD ("-"),
	VIB ("~");
	
	private final String token;
	private final Integer edoSteps;
	
	public String getToken() {
		return token;
	}
	
	boolean needsPrecedingValue() {
		
		if (this == InputVal.HOLD ) {
			return true;
		}
		if (this == InputVal.VIB ) {
			return true;
		}
		return false;
	}

	public OptionalInt getEdoSteps() {
		return edoSteps == null ? OptionalInt.empty() : OptionalInt.of(edoSteps);
	}
	
	public static Optional<InputVal> lookup(String token) {
		for (InputVal inputVal : values() ) {
			if (inputVal.token.contentEquals(token)) {
				return Optional.of(inputVal);
			}
		}
		return Optional.empty();
	}
	
	InputVal(int edoSteps, String token) {
		this.edoSteps = edoSteps;
		this.token = token;
	}
	InputVal(int i) {
		this.edoSteps = i;
		this.token = toString().replace("_",""); 
	}
	
	InputVal(String token) {
		this.token = token;
		this.edoSteps = null;
	}
		
}