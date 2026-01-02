package tabsequencer.config;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class CanvasesConfig {
	
	
	
	public static CanvasesConfig fromXMLElement(Element e) {
		List<CanvasConfig> canvases= new ArrayList<>();
		NodeList kids = e.getChildNodes();
		for (int i = 0; i < kids.getLength(); i++) {
			if (kids.item(i).getNodeType() != Node.ELEMENT_NODE) {
				continue;
			}
			Element kid = (Element) kids.item(i);
			if (kid.getTagName().contentEquals("stringCanvas")) {
				canvases.add(StringCanvasConfig.fromXMLElement(kid));
			} else if (kid.getTagName().contentEquals("drumCanvas")) {
				canvases.add(DrumCanvasConfig.fromXMLElement(kid));
			}
		}
		return new CanvasesConfig(canvases);
	}
	
	public static CanvasesConfig getXMLInstance(){
		try {
			File f = Stream.of("etc","config").map(d->new File(d+"/canvases.xml"))
					.filter(a->a.exists()).findFirst().get();
			SchemaFactory schemaF = SchemaFactory.newInstance("http://www.w3.org/2001/XMLSchema");
			Schema schema = schemaF.newSchema(new File("schemas/canvases.xsd"));
			
			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			dbf.setNamespaceAware(true);
			dbf.setSchema(schema);
			DocumentBuilder db = dbf.newDocumentBuilder();
			Document doc = db.parse(f);
			Element root = doc.getDocumentElement();
			return fromXMLElement(root);
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}

	}
	
	public Element toXMLElement(Document doc, String tagName) {
		Element toReturn = doc.createElement(tagName);
		for (CanvasConfig canvas : canvases) {
			switch(canvas.getType()) {
			case DRUM:
				toReturn.appendChild(canvas.toXMLElement(doc,"drumCanvas"));
				break;
			case STRING:
				toReturn.appendChild(canvas.toXMLElement(doc,"stringCanvas"));
				break;
			}
		}
		return toReturn;
	}
	
	@Override
	public String toString() {
		return "CanvasesConfig [canvases=" + canvases + "]";
	}

	private final List<CanvasConfig> canvases;
	public CanvasesConfig(List<CanvasConfig> canvases) {
		this.canvases = Collections.unmodifiableList(canvases);
	}
	public List<CanvasConfig> getCanvases() {
		return canvases;
	}
}