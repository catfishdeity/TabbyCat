package tabsequencer.config;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class DrumCanvasConfig extends CanvasConfig {
	private final List<PercToken> tokens;
	private final List<PercRowType> rowTypes;
	
	protected DrumCanvasConfig(String name, File soundfontFile, int bank, int program,List<PercRowType> rowTypes, List<PercToken> tokens) {
		super(name, soundfontFile, bank, program);
		this.rowTypes = Collections.unmodifiableList(rowTypes);
		this.tokens = Collections.unmodifiableList(tokens);
	}
	
	public List<PercRowType> getRowTypes() {
		return rowTypes;
	}
	public List<PercToken> getTokens() {
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
		List<PercToken> tokens = 
				IntStream.range(0,tokenNodes.getLength()).mapToObj(i->PercToken.fromXMLElement((Element) tokenNodes.item(i)))
				.collect(Collectors.toList());
				
		return new DrumCanvasConfig(name,soundfontFile,bank,program,rowTypes,tokens);		
	}

	@Override
	public Element toXMLElement(Document doc, String tagName) {
		Element e = doc.createElement(tagName);
		getSoundfontFile().ifPresent(f -> {
			e.setAttribute("soundfontFile", f.toString());
		});
		e.setAttribute("name", getName());
		e.setAttribute("bank", getBank()+"");
		e.setAttribute("program", getProgram()+"");
		
		for (PercRowType rowType : rowTypes) {
			Element rowE = doc.createElement("row");
			e.appendChild(rowE);
			rowE.setAttribute("type", rowType.toString().toUpperCase());			
		}
		for (PercToken token : tokens) {
			Element tokenE = doc.createElement("token");
			e.appendChild(tokenE);
			tokenE.setAttribute("token", token.getToken());
			tokenE.setAttribute("position", token.getPosition().toString().toUpperCase());
			tokenE.setAttribute("midiNumber", token.getMidiNumber()+"");
		}
		return e;
	}

	@Override
	public int getRowCount() {
		return rowTypes.size();
	}

	@Override
	public boolean willAccept(String token, int row) {
		if (row >= rowTypes.size()) {
			return false;
		}
		for (PercToken percToken : tokens) {
			if (percToken.getToken().toUpperCase().contentEquals(token.toUpperCase())) {
				if (percToken.getPosition() == rowTypes.get(row)) {
					return true;
				}
			}
		}
		return false;
	}
		
	
}