package io.github.fvarrui.javapackager.packagers;

import static org.apache.commons.lang3.StringUtils.defaultIfBlank;

import java.io.File;
import java.util.Arrays;

import org.apache.commons.lang3.StringUtils;

import io.github.fvarrui.javapackager.model.MacConfig;
import io.github.fvarrui.javapackager.utils.CommandUtils;
import io.github.fvarrui.javapackager.utils.FileUtils;
import io.github.fvarrui.javapackager.utils.Logger;
import io.github.fvarrui.javapackager.utils.ThreadUtils;
import io.github.fvarrui.javapackager.utils.VelocityUtils;

/**
 * Creates a DMG image file including all app folder's content only for MacOS so
 * app could be easily distributed
 */
public class GenerateDmg extends ArtifactGenerator {

	public GenerateDmg() {
		super("DMG image");
	}
	
	@Override
	public File apply(Packager packager) throws Exception {
		MacPackager macPackager = (MacPackager) packager;
		
		if (!macPackager.getMacConfig().isGenerateDmg()) {
			Logger.warn(getArtifactName() + " generation skipped by 'macConfig.generateDmg' property!");
			return null;
		}

		File appFolder = macPackager.getAppFolder();
		File assetsFolder = macPackager.getAssetsFolder();
		String name = macPackager.getName();
		File outputDirectory = macPackager.getOutputDirectory();
		File iconFile = macPackager.getIconFile();
		String version = macPackager.getVersion();
		MacConfig macConfig = macPackager.getMacConfig();
		
		// sets volume name if blank
		String volumeName = defaultIfBlank(macConfig.getVolumeName(), name);
		
		// removes whitespaces from volume name
		if (StringUtils.containsWhitespace(volumeName)) {
			volumeName = volumeName.replaceAll(" ", "");
			Logger.warn("Whitespaces has been removed from volume name: " + volumeName);
		}
		
		// final dmg file
		File dmgFile = new File(outputDirectory, name + "_" + version + ".dmg");
		
		// temp dmg file
		File tempDmgFile = new File(assetsFolder, dmgFile.getName());

		// mount dir
		File mountFolder = new File("/Volumes/" + volumeName);

		// creates a symlink to Applications folder
		Logger.info("Creating Applications link");
		File targetFolder = new File("/Applications");
		File linkFile = new File(appFolder, "Applications");
		FileUtils.createSymlink(linkFile, targetFolder);

		// copies background file
		Logger.info("Copying background image");
		File backgroundFolder = FileUtils.mkdir(appFolder, ".background");
		File backgroundFile = new File(backgroundFolder, "background.png");
		if (macConfig.getBackgroundImage() != null)
			FileUtils.copyFileToFile(macConfig.getBackgroundImage(), backgroundFile);
		else 
			FileUtils.copyResourceToFile("/mac/background.png", backgroundFile);
		
		// copies volume icon
		Logger.info("Copying icon file: " + iconFile.getAbsolutePath());
		File volumeIcon = (macConfig.getVolumeIcon() != null) ? macConfig.getVolumeIcon() : iconFile;  
		FileUtils.copyFileToFile(volumeIcon, new File(appFolder, ".VolumeIcon.icns"));
		
		// creates image
		Logger.info("Creating image: " + tempDmgFile.getAbsolutePath());
		CommandUtils.execute("hdiutil", "create", "-srcfolder", appFolder, "-volname", volumeName, "-fs", "HFS+", "-format", "UDRW", tempDmgFile);
		
		// mounts image
		Logger.info("Mounting image: " + tempDmgFile.getAbsolutePath());
		String result = CommandUtils.execute("hdiutil", "attach", "-readwrite", "-noverify", "-noautoopen", tempDmgFile);
		String deviceName = Arrays.asList(result.split("\n"))
									.stream()
									.filter(s -> s.contains(mountFolder.getAbsolutePath()))
									.map(s -> StringUtils.normalizeSpace(s))
									.map(s -> s.split(" ")[0])
									.findFirst().get();
		Logger.info("- Device name: " + deviceName);
		
		// pause to prevent occasional "Can't get disk" (-1728) issues 
		// https://github.com/seltzered/create-dmg/commit/5fe7802917bb85b40c0630b026d33e421db914ea
		ThreadUtils.sleep(2000L);
		
		// renders applescript 
		Logger.info("Rendering DMG customization applescript ... ");
		File applescriptFile = new File(assetsFolder, "customize-dmg.applescript");
		VelocityUtils.render("/mac/customize-dmg.applescript.vtl", applescriptFile, macPackager);
		Logger.info("Applescript rendered in " + applescriptFile.getAbsolutePath() + "!");
		
		// runs applescript 
		Logger.info("Running applescript");
		CommandUtils.execute("/usr/bin/osascript", applescriptFile, volumeName);
	
		// makes sure it's not world writeable
		Logger.info("Fixing permissions...");
		CommandUtils.execute("chmod", "-Rf", "go-w", mountFolder);
		
		// makes the top window open itself on mount:
		Logger.info("Blessing ...");
		CommandUtils.execute("bless", "--folder", mountFolder, "--openfolder", mountFolder);

		// tells the volume that it has a special file attribute
		CommandUtils.execute("SetFile", "-a", "C", mountFolder);
		
		// unmounts
		Logger.info("Unmounting disk image...");
		CommandUtils.execute("hdiutil", "detach", deviceName);
		
		// compress image
		Logger.info("Compressing disk image...");
		CommandUtils.execute("hdiutil", "convert", tempDmgFile, "-format", "UDZO", "-imagekey", "zlib-level=9", "-o", dmgFile);
		tempDmgFile.delete();

		// checks if dmg file was created
		if (!dmgFile.exists()) {
			throw new Exception(getArtifactName() + " generation failed!");
		}
		
		return dmgFile;
	}
	
}
