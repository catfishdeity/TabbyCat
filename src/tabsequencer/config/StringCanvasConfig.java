package tabsequencer.config;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.IntStream;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class StringCanvasConfig extends CanvasConfig {


	@Override
	public CanvasType getType() {
		return CanvasType.STRING;
	}
	
	public static StringCanvasConfig fromXMLElement(Element e) {
		String name = e.getAttribute("name");
		File soundfontFile = null;
		if (e.hasAttribute("soundfontFile")) {
			soundfontFile = new File(e.getAttribute("soundfontFile"));
		}
		int bank = Integer.parseInt(e.getAttribute("bank"));
		int program = Integer.parseInt(e.getAttribute("program"));
		
		NodeList stringNodes = e.getElementsByTagName("string");
		double[] edoSteps = 
				IntStream.range(0, stringNodes.getLength())
				.mapToDouble(i -> Double.parseDouble(((Element) stringNodes.item(i))
						.getAttribute("steps"))).toArray();
		Map<String,Double> additionalPitchMap = new HashMap<>();
		NodeList additionalPitchNodes = e.getElementsByTagName("additionalPitch");
		for (int i = 0; i < additionalPitchNodes.getLength(); i++) {
			Element node = (Element) additionalPitchNodes.item(i);
			String token = node.getAttribute("token");
			double edoStep = Double.parseDouble(node.getAttribute("steps"));			
			additionalPitchMap.put(token, edoStep);
		}
		
		int maxFrets = Integer.parseInt(e.getAttribute("maxFrets"));
		int maxHarmonic = Integer.parseInt(e.getAttribute("maxHarmonic"));
		int fretStepSkip = Integer.parseInt(e.getAttribute("fretStepSkip"));
		double ed2 = Double.parseDouble(e.getAttribute("ed2"));
		double baseFrequency = Double.parseDouble(e.getAttribute("baseFrequency"));
		
		
		return new StringCanvasConfig(edoSteps,name,maxFrets,maxHarmonic,ed2,soundfontFile,bank,program,fretStepSkip,baseFrequency,additionalPitchMap);
	}
	
	private final double[] edoSteps;	
	private final int maxFrets, maxHarmonic, fretStepSkip;
	private final double ed2, baseFrequency;
	
	private final Map<String,Double> additionalPitchMap;
	public StringCanvasConfig(double[] edoSteps, String name, int maxFrets, int maxHarmonic, double ed2, File soundfontFile,
			int bank, int program, int fretStepSkip, double baseFrequency, Map<String,Double> additionalPitchMap) {
		super(name,soundfontFile,bank,program);
		this.edoSteps = edoSteps;		
		this.maxFrets = maxFrets;
		this.maxHarmonic = maxHarmonic;
		this.ed2 = ed2;
		this.fretStepSkip = fretStepSkip;
		this.baseFrequency = baseFrequency;
		this.additionalPitchMap = Collections.unmodifiableMap(additionalPitchMap);			
	}
	
	public double getBaseFrequency() {
		return baseFrequency;
	}
	
	public int getFretStepSkip() {
		return fretStepSkip;
	}
	
	public Map<String,Double> getAdditionalPitchMap() {
		return additionalPitchMap;
	}

	public double[] getEdoSteps(){
		return edoSteps;
	}

	public int getMaxFrets() {
		return maxFrets;
	}
	

	public double getEd2() {
		return ed2;
	}

	public int getMaxHarmonic() {
		return maxHarmonic;
	}
	
}