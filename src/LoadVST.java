import java.io.File;
import java.io.FileNotFoundException;

import com.synthbot.audioplugin.vst.JVstLoadException;
import com.synthbot.audioplugin.vst.vst2.JVstHost2;

public class LoadVST {
	public static void main(String[] args) {
		JVstHost2 vst;
		try {
		  vst = JVstHost2.newInstance(new File("Surge Synth Team/Surge XT.vst3/Contents/x86_64-win"), 44100, 512);
		} catch (FileNotFoundException fnfe) {
		  fnfe.printStackTrace(System.err);
		} catch (JVstLoadException jvle) {
		  jvle.printStackTrace(System.err);
		}
	}
}
