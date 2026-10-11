package tabsequencer.config;

import java.awt.Point;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import tabsequencer.events.ControlEvent;
import tabsequencer.events.ProgramChange;
import tabsequencer.events.ShuffleEvent;
import tabsequencer.events.StickyNote;
import tabsequencer.events.TempoEvent;
import tabsequencer.events.TimeSignatureEvent;
import tabsequencer.events.TuningChangeEvent;

public class CanvasesConfig {

	public static CanvasesConfig fromXMLElement(Element e) {
		return fromXMLElement(e, null);
	}

	public static CanvasesConfig fromXMLElement(Element e, Map<String, Map<Point, ControlEvent>> eventDataByCanvas) {
		List<CanvasConfig> canvases = new ArrayList<>();
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
			} else if (kid.getTagName().contentEquals("eventCanvas")) {
				EventCanvasConfig ecc = EventCanvasConfig.fromXMLElement(kid);
				canvases.add(ecc);
				if (eventDataByCanvas != null) {
					loadEventChildren(kid, ecc.getName(), eventDataByCanvas);
				}
			}
		}
		return new CanvasesConfig(canvases);
	}

	private static void loadEventChildren(Element eventCanvasEl, String canvasName,
			Map<String, Map<Point, ControlEvent>> eventDataByCanvas) {
		NodeList eventNodes = eventCanvasEl.getElementsByTagName("event");
		for (int i = 0; i < eventNodes.getLength(); i++) {
			Element eventNode = (Element) eventNodes.item(i);
			int t = Integer.parseInt(eventNode.getAttribute("t"));
			int row = Integer.parseInt(eventNode.getAttribute("r"));
			Point p = new Point(t, row);
			Map<Point, ControlEvent> evData = eventDataByCanvas.computeIfAbsent(canvasName, k -> new java.util.HashMap<>());

			NodeList timeSignatureNodes = eventNode.getElementsByTagName("timeSignature");
			for (int k = 0; k < timeSignatureNodes.getLength(); k++)
				evData.put(p, TimeSignatureEvent.fromXMLElement((Element) timeSignatureNodes.item(k)));
			NodeList tempoNodes = eventNode.getElementsByTagName("tempo");
			for (int k = 0; k < tempoNodes.getLength(); k++)
				evData.put(p, TempoEvent.fromXMLElement((Element) tempoNodes.item(k)));
			NodeList shuffleNodes = eventNode.getElementsByTagName("shuffle");
			for (int k = 0; k < shuffleNodes.getLength(); k++)
				evData.put(p, ShuffleEvent.fromXMLElement((Element) shuffleNodes.item(k)));
			NodeList programChangeNodes = eventNode.getElementsByTagName("programChange");
			for (int k = 0; k < programChangeNodes.getLength(); k++)
				evData.put(p, ProgramChange.fromXMLElement((Element) programChangeNodes.item(k)));
			NodeList stickyNodes = eventNode.getElementsByTagName("note");
			for (int k = 0; k < stickyNodes.getLength(); k++)
				evData.put(p, StickyNote.fromXMLElement((Element) stickyNodes.item(k)));
			NodeList tuningNodes = eventNode.getElementsByTagName("tuningChange");
			for (int k = 0; k < tuningNodes.getLength(); k++)
				evData.put(p, TuningChangeEvent.fromXMLElement((Element) tuningNodes.item(k)));
		}
	}

	public static CanvasesConfig getXMLInstance() {
		try {
			File f = Stream.of("etc", "config").map(d -> new File(d + "/canvases.xml"))
					.filter(a -> a.exists()).findFirst().get();
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

	public Element toXMLElement(Document doc, String tagName,
			Map<String, Map<Point, ControlEvent>> eventDataByCanvas) {
		Element toReturn = doc.createElement(tagName);
		for (CanvasConfig canvas : canvases) {
			switch (canvas.getType()) {
			case DRUM:
				toReturn.appendChild(canvas.toXMLElement(doc, "drumCanvas"));
				break;
			case STRING:
				toReturn.appendChild(canvas.toXMLElement(doc, "stringCanvas"));
				break;
			case EVENT:
				Element evEl = canvas.toXMLElement(doc, "eventCanvas");
				if (eventDataByCanvas != null) {
					Map<Point, ControlEvent> evData = eventDataByCanvas.get(canvas.getName());
					if (evData != null) {
						evData.entrySet().forEach(entry -> {
							Element eventE = doc.createElement("event");
							eventE.setAttribute("t", entry.getKey().x + "");
							eventE.setAttribute("r", entry.getKey().y + "");
							eventE.appendChild(entry.getValue().toXMLElement(doc));
							evEl.appendChild(eventE);
						});
					}
				}
				toReturn.appendChild(evEl);
				break;
			}
		}
		return toReturn;
	}

	// Legacy overload (no event data) — used by getXMLInstance template loading
	public Element toXMLElement(Document doc, String tagName) {
		return toXMLElement(doc, tagName, null);
	}

	public void swapCanvases(int i, int j) {
		Collections.swap(canvases, i, j);
	}

	public void removeCanvas(int i) {
		canvases.remove(i);
	}

	@Override
	public String toString() {
		return "CanvasesConfig [canvases=" + canvases + "]";
	}

	private final List<CanvasConfig> canvases;

	public CanvasesConfig(List<CanvasConfig> canvases) {
		this.canvases = new ArrayList<>(canvases);
	}

	public List<CanvasConfig> getCanvases() {
		return canvases;
	}
}
