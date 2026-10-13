package tabsequencer;

public final class NoteCell {
    public static final int DEFAULT_VELOCITY = 100;
    public static final int DEFAULT_PAN = 64;
    public static final int DEFAULT_OFFSET_MS = 0;

    public final String token;
    public final int velocity;  // 0–127
    public final int pan;       // 0–127
    public final int offsetMs;  // signed ms offset from computed beat position

    public NoteCell(String token, int velocity, int pan, int offsetMs) {
        this.token = token;
        this.velocity = velocity;
        this.pan = pan;
        this.offsetMs = offsetMs;
    }

    public NoteCell withToken(String token)       { return new NoteCell(token, velocity, pan, offsetMs); }
    public NoteCell withVelocity(int velocity)    { return new NoteCell(token, velocity, pan, offsetMs); }
    public NoteCell withPan(int pan)              { return new NoteCell(token, velocity, pan, offsetMs); }
    public NoteCell withOffsetMs(int offsetMs)    { return new NoteCell(token, velocity, pan, offsetMs); }

    public static NoteCell defaultOf(String token) {
        return new NoteCell(token, DEFAULT_VELOCITY, DEFAULT_PAN, DEFAULT_OFFSET_MS);
    }
}
