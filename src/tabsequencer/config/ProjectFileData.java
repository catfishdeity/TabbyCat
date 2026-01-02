package tabsequencer.config;

import java.awt.Point;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import tabsequencer.InstrumentDataKey;
import tabsequencer.events.ControlEvent;
import tabsequencer.events.ProgramChange;
import tabsequencer.events.StickyNote;
import tabsequencer.events.TempoEvent;
import tabsequencer.events.TimeSignatureEvent;

public class ProjectFileData {
	private final CanvasesConfig canvases;
	private final Map<Point, ControlEvent> eventData;
	private final Map<InstrumentDataKey, String> instrumentData;
	private final AtomicInteger cursorT, repeatT, playbackStartT, selectedRow, selectedCanvas, viewT,tempo,playbackT,initialTempo;
	
	private final AtomicInteger lassoRow0 = new AtomicInteger(-1);
	private final AtomicInteger lassoT0 = new AtomicInteger(-1);
	private String songName = "Untitled";
	private String artistName = "Unknown Artist";

	public ProjectFileData(CanvasesConfig canvases) {
		this.canvases = canvases;
		this.eventData = new HashMap<>();
		this.instrumentData = new HashMap<>();
		this.cursorT = new AtomicInteger(0);
		this.repeatT = new AtomicInteger(-1);
		this.playbackStartT = new AtomicInteger(0);
		this.selectedRow = new AtomicInteger(0);
		this.selectedCanvas = new AtomicInteger(0);
		this.viewT = new AtomicInteger(-4);
		this.tempo = new AtomicInteger(120);
		this.initialTempo = new AtomicInteger(120);
		this.playbackT = new AtomicInteger(0);
	}
	
	
	public AtomicInteger getInitialTempo() {
		return initialTempo;
	}

	public String getSongName() {
		return songName;
	}



	public void setSongName(String songName) {
		this.songName = songName;
	}



	public String getArtistName() {
		return artistName;
	}



	public void setArtistName(String artistName) {
		this.artistName = artistName;
	}



	public AtomicInteger getLassoRow0() {
		return lassoRow0;
	}



	public AtomicInteger getLassoT0() {
		return lassoT0;
	}



	public void handleCharInput(char c) {
		// TODO reimplement, move out of the main class
	}

	public Element toXMLElement(Document doc) {
		Element e = doc.createElement("tabProject");
		e.setAttribute("xmlns", "tabby");
		e.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");		
		e.setAttribute("xsi:schemaLocation","tabby ../schemas/projectFiles.xsd");
		e.setAttribute("songName",songName);
		e.setAttribute("artistName",artistName);
		e.setAttribute("viewT",getViewT().get()+"");
		e.setAttribute("cursorT",cursorT.get()+"");
		e.setAttribute("repeatT",repeatT.get()+"");
		e.setAttribute("playbackStartT",playbackStartT.get()+"");
		e.setAttribute("playbackT",playbackT.get()+"");
		e.setAttribute("selectedRow",selectedRow.get()+"");
		e.setAttribute("selectedCanvas",selectedCanvas.get()+"");
		e.setAttribute("tempo",tempo.get()+"");
		e.appendChild(canvases.toXMLElement(doc, "canvases"));
		Element eventTabData = doc.createElement("eventTabData");
		e.appendChild(eventTabData);
		eventData.entrySet().forEach(entry -> {
			Element eventE = doc.createElement("event");
			eventE.setAttribute("t", entry.getKey().x+"");
			eventE.setAttribute("r", entry.getKey().y+"");
			eventE.appendChild(entry.getValue().toXMLElement(doc));
		});
		
		Map<String, List<Entry<InstrumentDataKey, String>>> grouped = 
				instrumentData.entrySet().stream().collect(Collectors.groupingBy(a->a.getKey().getInstrumentName()));
		grouped.keySet().forEach(name -> {
			Element instrumentDataElement = doc.createElement("instrumentTabData");
			e.appendChild(instrumentDataElement);
			instrumentDataElement.setAttribute("name", name);
			grouped.get(name).forEach(entry -> {
				Element eventElement = doc.createElement("event");
				instrumentDataElement.appendChild(eventElement);
				eventElement.setAttribute("t", entry.getKey().getTime()+"");
				eventElement.setAttribute("r", entry.getKey().getRow()+"");
				eventElement.setAttribute("v", entry.getValue());
			});
		});

		return e;
	}

	public static ProjectFileData fromXMLElement(Element e) {
		NodeList canvasesNodes = e.getElementsByTagName("canvases");
		CanvasesConfig canvases = IntStream.range(0, canvasesNodes.getLength())
				.mapToObj(i -> CanvasesConfig.fromXMLElement((Element) canvasesNodes.item(i))).findAny().get();

		ProjectFileData projectData = new ProjectFileData(canvases);

		NodeList eventTabDataNodes = e.getElementsByTagName("eventTabData");
		Element eventTabDataNode = (Element) eventTabDataNodes.item(0);
		NodeList eventNodes = eventTabDataNode.getElementsByTagName("event");
		for (int i = 0; i < eventNodes.getLength(); i++) {
			Element eventNode = (Element) eventNodes.item(i);
			int t = Integer.parseInt(eventNode.getAttribute("t"));
			int row = Integer.parseInt(eventNode.getAttribute("r"));
			Point p = new Point(t, row);
			// setCursorT(t);
			// eventCanvas.setSelectedRow(row);
			NodeList timeSignatureNodes = eventNode.getElementsByTagName("timeSignature");
			IntStream.range(0, timeSignatureNodes.getLength()).mapToObj(k -> (Element) timeSignatureNodes.item(k))
					.map(TimeSignatureEvent::fromXMLElement).forEach(timeSignature -> {
						projectData.eventData.put(p, timeSignature);

					});
			NodeList tempoNodes = eventNode.getElementsByTagName("tempo");
			IntStream.range(0, tempoNodes.getLength()).mapToObj(k -> (Element) tempoNodes.item(k))
					.map(TempoEvent::fromXMLElement).forEach(tempo -> {
						projectData.eventData.put(p, tempo);
					});
			NodeList programChangeNodes = eventNode.getElementsByTagName("programChange");
			IntStream.range(0, programChangeNodes.getLength()).mapToObj(k -> (Element) programChangeNodes.item(k))
					.map(ProgramChange::fromXMLElement).forEach(programChange -> {
						projectData.eventData.put(p, programChange);
					});
			NodeList stickyNodes = eventNode.getElementsByTagName("note");
			IntStream.range(0, stickyNodes.getLength()).mapToObj(k -> (Element) stickyNodes.item(k))
					.map(StickyNote::fromXMLElement).forEach(stickyNote -> {
						projectData.eventData.put(p, stickyNote);
					});

		}

		NodeList instrumentTabDataNodes = e.getElementsByTagName("instrumentTabData");
		for (int i = 0; i < instrumentTabDataNodes.getLength(); i++) {
			Element instrumentTabDataNode = (Element) instrumentTabDataNodes.item(i);
			String name = instrumentTabDataNode.getAttribute("name");
			NodeList instrumentEventNodes = instrumentTabDataNode.getElementsByTagName("event");
			for (int j = 0; j < instrumentEventNodes.getLength(); j++) {
				Element instrumentEventNode = (Element) instrumentEventNodes.item(j);
				int time = Integer.parseInt(instrumentEventNode.getAttribute("t"));
				int row = Integer.parseInt(instrumentEventNode.getAttribute("r"));
				String value = instrumentEventNode.getAttribute("v");
				InstrumentDataKey key = new InstrumentDataKey(name, time, row);
				projectData.instrumentData.put(key, value);
			}
		}

		projectData.cursorT.set(Integer.parseInt(e.getAttribute("cursorT")));
		projectData.repeatT.set(Integer.parseInt(e.getAttribute("repeatT")));
		projectData.playbackStartT.set(Integer.parseInt(e.getAttribute("playbackStartT")));
		projectData.playbackT.set(Integer.parseInt(e.getAttribute("playbackT")));
		projectData.selectedRow.set(Integer.parseInt(e.getAttribute("selectedRow")));
		projectData.selectedCanvas.set(Integer.parseInt(e.getAttribute("selectedCanvas")));
		projectData.getViewT().set(Integer.parseInt(e.getAttribute("viewT")));
		projectData.tempo.set(Integer.parseInt(e.getAttribute("tempo")));
		projectData.songName = e.getAttribute("songName");
		projectData.artistName = e.getAttribute("artistName");
		
		return projectData;
	}

	public AtomicInteger getCursorT() {
		return cursorT;
	}

	public AtomicInteger getRepeatT() {
		return repeatT;
	}

	public AtomicInteger getPlaybackStartT() {
		return playbackStartT;
	}

	public AtomicInteger getSelectedRow() {
		return selectedRow;
	}

	public AtomicInteger getSelectedCanvas() {
		return selectedCanvas;
	}

	public CanvasesConfig getCanvases() {
		return canvases;
	}

	public Map<Point, ControlEvent> getEventData() {
		return eventData;
	}

	public Map<InstrumentDataKey, String> getInstrumentData() {
		return instrumentData;
	}

	public AtomicInteger getTempo() {
		return tempo;
	}

	public AtomicInteger getPlaybackT() {
		return playbackT;
	}

	public AtomicInteger getViewT() {
		return viewT;
	}

}
