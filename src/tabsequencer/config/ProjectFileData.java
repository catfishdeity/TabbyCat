package tabsequencer.config;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import tabsequencer.InstrumentDataKey;
import tabsequencer.events.ControlEvent;
import tabsequencer.events.ProgramChange;
import tabsequencer.events.ShuffleEvent;
import tabsequencer.events.StickyNote;
import tabsequencer.events.TempoEvent;
import tabsequencer.events.TimeSignatureEvent;

public class ProjectFileData {
	private final CanvasesConfig canvases;
	private final Map<String, Map<Point, ControlEvent>> eventDataByCanvas;
	private final Map<InstrumentDataKey, String> instrumentData;
	private final AtomicInteger cursorT, repeatT0, repeatT1, playbackStartT, selectedRow, selectedCanvas, viewT, tempo, playbackT, initialTempo, shuffle;

	private String songName = "Untitled";
	private String artistName = "Unknown Artist";
	private double uiScale = -1.0; // -1 means "use app default"
	private double rainbowBrightness = -1.0; // -1 means "use app default"
	private double scrollbarBrightness = -1.0; // -1 means "use app default"
	private String gridFontFamily = null; // null means "use app default"
	private int gridFontSize = -1; // -1 means "use app default"

	public ProjectFileData(CanvasesConfig canvases) {
		this.canvases = canvases;
		this.eventDataByCanvas = new HashMap<>();
		this.instrumentData = new HashMap<>();
		this.cursorT = new AtomicInteger(0);
		this.repeatT0 = new AtomicInteger(-1);
		this.repeatT1 = new AtomicInteger(-1);
		this.playbackStartT = new AtomicInteger(0);
		this.selectedRow = new AtomicInteger(0);
		this.selectedCanvas = new AtomicInteger(0);
		this.viewT = new AtomicInteger(0);
		this.tempo = new AtomicInteger(120);
		this.initialTempo = new AtomicInteger(120);
		this.playbackT = new AtomicInteger(0);
		this.shuffle = new AtomicInteger(0);
	}

	public Optional<CanvasConfig> getCanvasConfig(String name) {
		return canvases.getCanvases().stream()
				.filter(a -> a.getName().equals(name))
				.findFirst();
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

	public double getUiScale() {
		return uiScale;
	}

	public void setUiScale(double uiScale) {
		this.uiScale = uiScale;
	}

	public double getRainbowBrightness() {
		return rainbowBrightness;
	}

	public void setRainbowBrightness(double rainbowBrightness) {
		this.rainbowBrightness = rainbowBrightness;
	}

	public double getScrollbarBrightness() {
		return scrollbarBrightness;
	}

	public void setScrollbarBrightness(double scrollbarBrightness) {
		this.scrollbarBrightness = scrollbarBrightness;
	}

	public String getGridFontFamily() { return gridFontFamily; }
	public void setGridFontFamily(String gridFontFamily) { this.gridFontFamily = gridFontFamily; }
	public int getGridFontSize() { return gridFontSize; }
	public void setGridFontSize(int gridFontSize) { this.gridFontSize = gridFontSize; }

	public void handleCharInput(char c) {
		// TODO reimplement, move out of the main class
	}

	/** Returns (creating if absent) the event data map for the named event canvas. */
	public Map<Point, ControlEvent> getEventData(String canvasName) {
		return eventDataByCanvas.computeIfAbsent(canvasName, k -> new HashMap<>());
	}

	/** Removes and returns the event data map for a canvas (used when deleting a canvas). */
	public Map<Point, ControlEvent> removeEventData(String canvasName) {
		return eventDataByCanvas.remove(canvasName);
	}

	public Element toXMLElement(Document doc) {
		Element e = doc.createElement("tabProject");
		e.setAttribute("xmlns", "tabby");
		e.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");
		e.setAttribute("xsi:schemaLocation", "tabby ../schemas/projectFiles.xsd");
		e.setAttribute("songName", songName);
		e.setAttribute("artistName", artistName);
		e.setAttribute("viewT", getViewT().get() + "");
		e.setAttribute("cursorT", cursorT.get() + "");
		e.setAttribute("repeatT0", repeatT0.get() + "");
		e.setAttribute("repeatT1", repeatT1.get() + "");
		e.setAttribute("playbackStartT", playbackStartT.get() + "");
		e.setAttribute("playbackT", playbackT.get() + "");
		e.setAttribute("selectedRow", selectedRow.get() + "");
		e.setAttribute("selectedCanvas", selectedCanvas.get() + "");
		e.setAttribute("tempo", tempo.get() + "");
		e.setAttribute("shuffle", shuffle.get() + "");
		if (uiScale > 0) e.setAttribute("uiScale", String.format("%.1f", uiScale));
		if (rainbowBrightness > 0) e.setAttribute("rainbowBrightness", String.format("%.1f", rainbowBrightness));
		if (scrollbarBrightness > 0) e.setAttribute("scrollbarBrightness", String.format("%.1f", scrollbarBrightness));
		if (gridFontFamily != null) e.setAttribute("gridFontFamily", gridFontFamily);
		if (gridFontSize > 0) e.setAttribute("gridFontSize", String.valueOf(gridFontSize));

		// Canvases (event data for eventCanvas elements is embedded inside)
		e.appendChild(canvases.toXMLElement(doc, "canvases", eventDataByCanvas));

		// Instrument tab data
		Map<String, List<Entry<InstrumentDataKey, String>>> grouped =
				instrumentData.entrySet().stream().collect(Collectors.groupingBy(a -> a.getKey().getInstrumentName()));
		grouped.keySet().forEach(name -> {
			Element instrumentDataElement = doc.createElement("instrumentTabData");
			e.appendChild(instrumentDataElement);
			instrumentDataElement.setAttribute("name", name);
			grouped.get(name).forEach(entry -> {
				Element eventElement = doc.createElement("event");
				instrumentDataElement.appendChild(eventElement);
				eventElement.setAttribute("t", entry.getKey().getTime() + "");
				eventElement.setAttribute("r", entry.getKey().getRow() + "");
				eventElement.setAttribute("v", entry.getValue());
			});
		});

		return e;
	}

	public static ProjectFileData fromXMLElement(Element e) {
		NodeList canvasesNodes = e.getElementsByTagName("canvases");
		Element canvasesEl = (Element) canvasesNodes.item(0);

		// Build the eventDataByCanvas map while parsing canvases (for eventCanvas elements)
		Map<String, Map<Point, ControlEvent>> eventDataByCanvas = new HashMap<>();
		CanvasesConfig canvases = CanvasesConfig.fromXMLElement(canvasesEl, eventDataByCanvas);

		// Backward compat: old files have a top-level <eventTabData> element.
		// If no event canvas was found in <canvases>, synthesize one named "Events".
		boolean hasEventCanvas = canvases.getCanvases().stream()
				.anyMatch(c -> c instanceof EventCanvasConfig);
		if (!hasEventCanvas) {
			NodeList eventTabDataNodes = e.getElementsByTagName("eventTabData");
			if (eventTabDataNodes.getLength() > 0) {
				Element eventTabDataNode = (Element) eventTabDataNodes.item(0);
				EventCanvasConfig legacyCanvas = new EventCanvasConfig("Events", 3);
				// Insert at position 0
				List<CanvasConfig> list = new ArrayList<>(canvases.getCanvases());
				list.add(0, legacyCanvas);
				canvases = new CanvasesConfig(list);

				// Load events from the legacy eventTabData
				Map<Point, ControlEvent> evData = eventDataByCanvas.computeIfAbsent("Events", k -> new HashMap<>());
				NodeList eventNodes = eventTabDataNode.getElementsByTagName("event");
				for (int i = 0; i < eventNodes.getLength(); i++) {
					Element eventNode = (Element) eventNodes.item(i);
					int t = Integer.parseInt(eventNode.getAttribute("t"));
					int row = Integer.parseInt(eventNode.getAttribute("r"));
					Point p = new Point(t, row);
					NodeList timeSignatureNodes = eventNode.getElementsByTagName("timeSignature");
					IntStream.range(0, timeSignatureNodes.getLength()).mapToObj(k -> (Element) timeSignatureNodes.item(k))
							.map(TimeSignatureEvent::fromXMLElement).forEach(ts -> evData.put(p, ts));
					NodeList tempoNodes = eventNode.getElementsByTagName("tempo");
					IntStream.range(0, tempoNodes.getLength()).mapToObj(k -> (Element) tempoNodes.item(k))
							.map(TempoEvent::fromXMLElement).forEach(te -> evData.put(p, te));
					NodeList shuffleNodes = eventNode.getElementsByTagName("shuffle");
					IntStream.range(0, shuffleNodes.getLength()).mapToObj(k -> (Element) shuffleNodes.item(k))
							.map(ShuffleEvent::fromXMLElement).forEach(se -> evData.put(p, se));
					NodeList programChangeNodes = eventNode.getElementsByTagName("programChange");
					IntStream.range(0, programChangeNodes.getLength()).mapToObj(k -> (Element) programChangeNodes.item(k))
							.map(ProgramChange::fromXMLElement).forEach(pc -> evData.put(p, pc));
					NodeList stickyNodes = eventNode.getElementsByTagName("note");
					IntStream.range(0, stickyNodes.getLength()).mapToObj(k -> (Element) stickyNodes.item(k))
							.map(StickyNote::fromXMLElement).forEach(sn -> evData.put(p, sn));
				}
			}
		}

		ProjectFileData projectData = new ProjectFileData(canvases);
		projectData.eventDataByCanvas.putAll(eventDataByCanvas);

		// Instrument tab data
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
		String repeatT0Attr = e.getAttribute("repeatT0");
		String repeatT1Attr = e.getAttribute("repeatT1");
		String repeatTAttr = e.getAttribute("repeatT");
		if (!repeatT0Attr.isEmpty()) {
			projectData.repeatT0.set(Integer.parseInt(repeatT0Attr));
		}
		if (!repeatT1Attr.isEmpty()) {
			projectData.repeatT1.set(Integer.parseInt(repeatT1Attr));
		} else if (!repeatTAttr.isEmpty()) {
			projectData.repeatT1.set(Integer.parseInt(repeatTAttr));
		}
		projectData.playbackStartT.set(Integer.parseInt(e.getAttribute("playbackStartT")));
		projectData.playbackT.set(Integer.parseInt(e.getAttribute("playbackT")));
		projectData.selectedRow.set(Integer.parseInt(e.getAttribute("selectedRow")));
		projectData.selectedCanvas.set(Integer.parseInt(e.getAttribute("selectedCanvas")));
		projectData.getViewT().set(Integer.parseInt(e.getAttribute("viewT")));
		projectData.tempo.set(Integer.parseInt(e.getAttribute("tempo")));
		String shuffleAttr = e.getAttribute("shuffle");
		if (!shuffleAttr.isEmpty()) {
			projectData.shuffle.set(Integer.parseInt(shuffleAttr));
		}
		projectData.songName = e.getAttribute("songName");
		projectData.artistName = e.getAttribute("artistName");
		String uiScaleAttr = e.getAttribute("uiScale");
		if (!uiScaleAttr.isEmpty()) {
			projectData.uiScale = Double.parseDouble(uiScaleAttr);
		}
		String rainbowBrightnessAttr = e.getAttribute("rainbowBrightness");
		if (!rainbowBrightnessAttr.isEmpty()) {
			projectData.rainbowBrightness = Double.parseDouble(rainbowBrightnessAttr);
		}
		String scrollbarBrightnessAttr = e.getAttribute("scrollbarBrightness");
		if (!scrollbarBrightnessAttr.isEmpty()) {
			projectData.scrollbarBrightness = Double.parseDouble(scrollbarBrightnessAttr);
		}
		String gridFontFamilyAttr = e.getAttribute("gridFontFamily");
		if (!gridFontFamilyAttr.isEmpty()) {
			projectData.gridFontFamily = gridFontFamilyAttr;
		}
		String gridFontSizeAttr = e.getAttribute("gridFontSize");
		if (!gridFontSizeAttr.isEmpty()) {
			projectData.gridFontSize = Integer.parseInt(gridFontSizeAttr);
		}

		return projectData;
	}

	public AtomicInteger getCursorT() {
		return cursorT;
	}

	public AtomicInteger getRepeatT0() {
		return repeatT0;
	}

	public AtomicInteger getRepeatT1() {
		return repeatT1;
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

	public Map<String, Map<Point, ControlEvent>> getEventDataByCanvas() {
		return eventDataByCanvas;
	}

	public Map<InstrumentDataKey, String> getInstrumentData() {
		return instrumentData;
	}

	public AtomicInteger getTempo() {
		return tempo;
	}

	public AtomicInteger getShuffle() {
		return shuffle;
	}

	public AtomicInteger getPlaybackT() {
		return playbackT;
	}

	public AtomicInteger getViewT() {
		return viewT;
	}
}
