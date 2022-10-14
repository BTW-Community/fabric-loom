package net.fabricmc.loom.task;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import com.google.common.base.Preconditions;
import org.gradle.api.Project;
import org.objectweb.asm.commons.Remapper;
import org.zeroturnaround.zip.ZipUtil;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.build.MixinRefmapHelper;
import net.fabricmc.loom.build.nesting.JarNester;
import net.fabricmc.loom.build.nesting.NestedJarProvider;
import net.fabricmc.loom.configuration.accesswidener.AccessWidenerJarProcessor;
import net.fabricmc.loom.configuration.providers.mappings.MappingsProvider;
import net.fabricmc.loom.util.TinyRemapperMappingsHelper;
import net.fabricmc.loom.util.ZipReprocessorUtil;
import net.fabricmc.stitch.util.Pair;
import net.fabricmc.tinyremapper.TinyUtils;

public class ReobfuscateJarTask extends RemapJarTask {
	@Override
	public void scheduleRemap(boolean isMainRemapTask) throws Throwable {
		Project project = getProject();
		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
		Path input = this.getInput().getAsFile().get().toPath();
		Path output = this.getArchivePath().toPath();

		if (!Files.exists(input)) {
			throw new FileNotFoundException(input.toString());
		}

		MappingsProvider mappingsProvider = extension.getMappingsProvider();

		String fromM = "named";
		String toM = "official";
		if (isMainRemapTask) {
			jarRemapper.addToClasspath(getRemapClasspath());

			jarRemapper.addMappings(TinyRemapperMappingsHelper.create(mappingsProvider.getMappings(), fromM, toM, false));
		}

		/*for (File mixinMapFile : extension.getAllMixinMappings()) {
			if (mixinMapFile.exists()) {
				jarRemapper.addMappings(TinyUtils.createTinyMappingProvider(mixinMapFile.toPath(), fromM, toM));
			}
		}*/

		// Add remap options to the jar remapper
		this.remapOptions(r -> r.extraRemapper(new Remapper()
		{
			/**
			 * Maps the internal name of a class to its new name. The default implementation of this method
			 * returns the given name, unchanged. Subclasses can override.
			 *
			 * @param internalName the internal name of a class.
			 * @return the new internal name.
			 */
			@Override
			public String map(String internalName) {
				if (internalName.contains("/")) {
					return internalName.replace("net/minecraft/src/", "");
				} else {
					return internalName;
				}
			}
		}));
		jarRemapper.addOptions(this.remapOptions);

		NestedJarProvider nestedJarProvider = getNestedJarProvider();
		nestedJarProvider.prepare(getProject());

		jarRemapper.scheduleRemap(input, output)
				.supplyAccessWidener((remapData, remapper) -> {
					if (getRemapAccessWidener().getOrElse(false) && extension.accessWidener != null) {
						AccessWidenerJarProcessor accessWidenerJarProcessor = extension.getJarProcessorManager().getByType(AccessWidenerJarProcessor.class);
						byte[] data;

						try {
							data = accessWidenerJarProcessor.getRemappedAccessWidener(remapper);
						} catch (IOException e) {
							throw new RuntimeException("Failed to remap access widener");
						}

						String awPath = accessWidenerJarProcessor.getAccessWidenerPath(remapData.input);
						Preconditions.checkNotNull(awPath, "Failed to find accessWidener in fabric.mod.json: " + remapData.input);

						return Pair.of(awPath, data);
					}

					return null;
				})
				.complete((data, accessWidener) -> {
					MixinRefmapHelper m = new MixinRefmapHelper(project);

					if (!Files.exists(output)) {
						throw new RuntimeException("Failed to remap " + input + " to " + output + " - file missing!");
					}

					project.getLogger().warn(extension.getRefmapName());

					if (m.addRefmapName(extension.getRefmapName(), output, getRemapClasspath())) {
						project.getLogger().debug("Transformed mixin reference maps in output JAR!");
					}

					if (getAddNestedDependencies().getOrElse(false)) {
						JarNester.nestJars(nestedJarProvider.provide(), output.toFile(), project.getLogger());
					}

					if (accessWidener != null) {
						boolean replaced = ZipUtil.replaceEntry(data.output.toFile(), accessWidener.getLeft(), accessWidener.getRight());
						Preconditions.checkArgument(replaced, "Failed to remap access widener");
					}

					if (isReproducibleFileOrder() || !isPreserveFileTimestamps()) {
						try {
							ZipReprocessorUtil.reprocessZip(output.toFile(), isReproducibleFileOrder(), isPreserveFileTimestamps());
						} catch (IOException e) {
							throw new RuntimeException("Failed to re-process jar", e);
						}
					}
				});
	}
}
