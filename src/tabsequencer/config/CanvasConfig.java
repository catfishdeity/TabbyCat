package tabsequencer.config;

import java.io.File;
import java.util.Optional;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import tabsequencer.CanvasEditMode;

public abstract class CanvasConfig {
	public abstract CanvasType getType();
	protected File soundfontFile;
	protected int bank, program;
	protected String name;
	// Unique runtime+serialization key — decoupled from display name to allow duplicate names
	protected String id = java.util.UUID.randomUUID().toString();

	public final String getId() { return id; }
	final void setId(String id) { this.id = id; }

	protected CanvasConfig(String name, File soundfontFile, int bank, int program) {
		this.name = name;
		this.soundfontFile = soundfontFile;
		this.bank = bank;
		this.program = program;
	}
	
	public abstract int getRowCount();
	
	public final Optional<File> getSoundfontFile() {
		return Optional.ofNullable(soundfontFile);
	}

	public final void setSoundfontFile(File f) {
		this.soundfontFile = f;
	}
	
	public abstract Element toXMLElement(Document doc, String tagName);

	public final String getName() {
		return name;
	}

	public final void setName(String name) {
		this.name = name;
	}

	public final int getBank() {
		return bank;
	}

	public final void setBank(int bank) {
		this.bank = bank;
	}

	public final int getProgram() {
		return program;
	}

	public final void setProgram(int program) {
		this.program = program;
	}

	public abstract boolean willAccept(String token, int row);

	protected boolean muted = false;
	public boolean isMuted() { return muted; }
	public void setMuted(boolean muted) { this.muted = muted; }

	protected CanvasEditMode editMode = CanvasEditMode.NOTE;
	public CanvasEditMode getEditMode() { return editMode; }
	public void setEditMode(CanvasEditMode editMode) { this.editMode = editMode; }
}