package tabsequencer.config;

import java.io.File;
import java.util.Optional;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

public abstract class CanvasConfig {
	public abstract CanvasType getType();
	protected final File soundfontFile;
	protected final int bank, program;
	protected final String name;
	
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
	
	public abstract Element toXMLElement(Document doc, String tagName);

	public final String getName() {
		return name;
	}

	public final int getBank() {
		return bank;
	}

	public final int getProgram() {
		return program;
	}

	public abstract boolean willAccept(String token, int row);
}