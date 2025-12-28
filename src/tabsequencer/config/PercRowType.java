package tabsequencer.config;

enum PercRowType {
	HAND, FOOT;
	
	public static PercRowType lookup(String token) {

		if (token.toUpperCase().contentEquals("FOOT")) {
			return FOOT;
		}
		return HAND;
	}
}