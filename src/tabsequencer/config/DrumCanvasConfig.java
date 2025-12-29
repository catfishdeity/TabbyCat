package tabsequencer.config;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class DrumCanvasConfig extends CanvasConfig {
	private final List<PercRowToken> tokens;
	private final List<PercRowType> rowTypes;
	
	protected DrumCanvasConfig(String name, File soundfontFile, int bank, int program,List<PercRowType> rowTypes, List<PercRowToken> tokens) {
		super(name, soundfontFile, bank, program);
		this.rowTypes = Collections.unmodifiableList(rowTypes);
		this.tokens = Collections.unmodifiableList(tokens);
	}
	
	public List<PercRowType> getRowTypes() {
		return rowTypes;
	}
	public List<PercRowToken> getTokens() {
		return tokens;
	}
	@Override
	public CanvasType getType() {
		return CanvasType.DRUM;
	}
	public static DrumCanvasConfig fromXMLElement(Element e) {
		String name = e.getAttribute("name");
		File soundfontFile = null;
		if (e.hasAttribute("soundfontFile")) {
			soundfontFile = new File(e.getAttribute("soundfontFile"));		}
		int bank = Integer.parseInt(e.getAttribute("bank"));
		int program = Integer.parseInt(e.getAttribute("program"));
		NodeList rowNodes = e.getElementsByTagName("row");
		List<PercRowType> rowTypes = 
				IntStream.range(0, rowNodes.getLength()).mapToObj(i->PercRowType.lookup(((Element) rowNodes.item(i)).getAttribute("type")))
				.collect(Collectors.toList());
				
		NodeList tokenNodes = e.getElementsByTagName("token");
		List<PercRowToken> tokens = 
				IntStream.range(0,tokenNodes.getLength()).mapToObj(i->PercRowToken.fromXMLElement((Element) tokenNodes.item(i)))
				.collect(Collectors.toList());
				
		return new DrumCanvasConfig(name,soundfontFile,bank,program,rowTypes,tokens);		
	}
		
	
}