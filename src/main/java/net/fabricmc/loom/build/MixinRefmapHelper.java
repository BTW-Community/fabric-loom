/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016, 2017, 2018 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.build;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.gradle.api.Project;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.Remapper;
import org.zeroturnaround.zip.ZipUtil;
import org.zeroturnaround.zip.transform.ByteArrayZipEntryTransformer;
import org.zeroturnaround.zip.transform.StringZipEntryTransformer;
import org.zeroturnaround.zip.transform.ZipEntryTransformer;
import org.zeroturnaround.zip.transform.ZipEntryTransformerEntry;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.stitch.util.Pair;
import net.fabricmc.tinyremapper.TinyRemapper;

public final class MixinRefmapHelper {
	protected Project project;
	protected TinyRemapper remapper_i2n;
	protected TinyRemapper remapper_n2i;
	protected TinyRemapper remapper_i2o;

	public MixinRefmapHelper(Project p) {
		project = p;
		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);

		try {
			remapper_i2n = extension.getMappingsProvider().mappedProvider.getTinyRemapper("intermediary", "named");
			remapper_i2o = extension.getMappingsProvider().mappedProvider.getTinyRemapper("intermediary", "official");
			remapper_n2i = extension.getMappingsProvider().mappedProvider.getTinyRemapper("named", "intermediary");
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public boolean addRefmapName(String filename, Path outputPath, Path[] classpath) {
		File output = outputPath.toFile();
		Set<String> mixinFilenames = findMixins(output, true);

		try {
			transformRefmap(filename, outputPath, classpath);
		} catch (IOException e) {
			e.printStackTrace();
		}

		if (mixinFilenames.size() > 0) {
			return ZipUtil.transformEntries(output, mixinFilenames.stream().map((f) -> new ZipEntryTransformerEntry(f, new StringZipEntryTransformer("UTF-8") {
				@Override
				protected String transform(ZipEntry zipEntry, String input) throws IOException {
					JsonObject json = LoomGradlePlugin.GSON.fromJson(input, JsonObject.class);

					if (!json.has("refmap")) {
						json.addProperty("refmap", filename);
					}

					return LoomGradlePlugin.GSON.toJson(json);
				}
			})).toArray(ZipEntryTransformerEntry[]::new));
		} else {
			return false;
		}
	}

	private void transformRefmap(String filename, Path outputPath, Path[] classpath) throws IOException {
		Map<String, String> methodMap = null;
		Map<String, Map<String, String>> remapped_cache = new HashMap<>();

		try {
			Field f = remapper_n2i.getClass().getDeclaredField("methodMap");
			f.setAccessible(true);
			methodMap = (Map<String, String>) f.get(remapper_n2i);
		} catch (IllegalAccessException | NoSuchFieldException e) {
			e.printStackTrace();
		}

		java.nio.file.FileSystem fs = FileSystems.newFileSystem(outputPath, null);

		if (Files.exists(fs.getPath(filename))) {
			InputStream fis = fs.provider().newInputStream(fs.getPath(filename));
			JsonObject json = LoomGradlePlugin.GSON.fromJson(new InputStreamReader(fis), JsonObject.class);
			fis.close();
			OutputStream fos = fs.provider().newOutputStream(fs.getPath(filename),
									StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE
								);
			for (Map.Entry<String, JsonElement> x : json.entrySet()) {
				JsonObject je = x.getValue().getAsJsonObject();

				if (x.getKey().equals("mappings")) {
					for (Map.Entry<String, JsonElement> y : je.entrySet()) {
						JsonObject mixin = y.getValue().getAsJsonObject();

						for (Map.Entry<String, JsonElement> item : mixin.entrySet()) {
							String tmp_intermediary = item.getValue().getAsString();
							String original_class = tmp_intermediary.substring(1, tmp_intermediary.indexOf(";"));
							String mapped_class = ((Remapper) (remapper_i2n.getRemapper())).map(original_class);

							if (mapped_class.equals(original_class)) {
								// Non-vanilla internal class!
								// Does original_class inherit from a vanilla class?
								// If not, everything is fine (except for interface implementations...?)

								// ! Might produce incorrect result due to overwrites and different ordering at runtime !
								for (Path p : classpath) {
									String current_class = original_class;

									while (true) {
										List<Pair<String, Boolean>> possibleSuperClasses = findSuperClass(p.toFile(), current_class);

										if (!possibleSuperClasses.isEmpty()) {
											// Whether one vanilla superclass has been found yet
											Optional<Pair<String, Boolean>> o =
													possibleSuperClasses.stream().filter(Pair::getRight).findFirst();
											if (o.isPresent()) {
												/*
												*	It does inherit from a vanilla class.
												*  Therefore we might need to remap the method as follows:
												*  LmixinTargetClass;nonMappedMethod(mappedArguments)
												*  						->
												*  LmixinTargetClass;mappedMethod(mappedArguments)
												*  But only if it is originally a vanilla inherited method!
												*  Now we need to find the mapped version of our method.
												*  It can be in the now found superclass, but it can also be in
												*  the classes further up.
												*/
												String superName = o.get().getLeft();
												String methodName = tmp_intermediary.substring(
														tmp_intermediary.indexOf(";") + 1, tmp_intermediary.indexOf("(")
												);
												String descriptor = ((Remapper) remapper_i2n.getRemapper())
														.mapMethodDesc(tmp_intermediary.substring(
															tmp_intermediary.indexOf("(")
													)
												);
												String mappedMethod = methodMap.get(superName + "/" + methodName + descriptor);

												if (mappedMethod != null && !mappedMethod.equals(methodName)) {
													project.getLogger().debug("Found a superclass: " + superName + " of " + original_class);
													String remapped_intermediary =
															tmp_intermediary.substring(0, tmp_intermediary.indexOf(";") + 1)
															+ mappedMethod + tmp_intermediary.substring(tmp_intermediary.indexOf("("));
													item.setValue(new JsonPrimitive(remapped_intermediary));
													HashMap<String, String> hm = new HashMap<>();
													hm.put(item.getKey(), remapped_intermediary);
													remapped_cache.put(y.getKey(), hm);
													break;
												} else {
													current_class = superName;
												}
											} else {
												current_class = possibleSuperClasses.get(0).getLeft();
											}
										} else {
											// No superclass found!
											break;
										}
									}
								}
							}
						}
					}
				} else if (x.getKey().equals("data")) {
					for (Map.Entry<String, JsonElement> d : je.entrySet()) {
						if (d.getKey().equals("named:intermediary")) {
							JsonObject je2 = d.getValue().getAsJsonObject();

							for (Map.Entry<String, JsonElement> y : je2.entrySet()) {
								JsonObject mixin = y.getValue().getAsJsonObject();

								for (Map.Entry<String, JsonElement> item : mixin.entrySet()) {
									if (remapped_cache.containsKey(y.getKey()) && remapped_cache.get(y.getKey()).containsKey(item.getKey())) {
										item.setValue(new JsonPrimitive(remapped_cache.get(y.getKey()).get(item.getKey())));
									}
								}
							}
						}
					}
				}
			}

			fos.write(LoomGradlePlugin.GSON.toJson(json).getBytes());
			fos.close();
		}

		fs.close();
	}

	private List<Pair<String, Boolean>> findSuperClass(File file, String cls) {
		List<Pair<String, Boolean>> ret = new ArrayList<>();
		ZipEntryTransformerEntry[] z = getTransformers(Collections.singleton(cls), ret);
		ZipUtil.transformEntries(file, z);
		return ret;
	}

	private ZipEntryTransformerEntry[] getTransformers(Set<String> classes, List<Pair<String, Boolean>> ret) {
		return classes.stream()
				.map(string -> new ZipEntryTransformerEntry(string.replaceAll("\\.", "/") + ".class", getTransformer(string, ret)))
				.toArray(ZipEntryTransformerEntry[]::new);
	}

	private ZipEntryTransformer getTransformer(String className, List<Pair<String, Boolean>> ret) {
		return new ByteArrayZipEntryTransformer() {
			@Override
			protected byte[] transform(ZipEntry zipEntry, byte[] input) {
				ClassReader reader = new ClassReader(input);
				ClassWriter writer = new ClassWriter(0);
				ClassVisitor classVisitor = new ClassVisitor(Opcodes.ASM8, writer) {
							@Override
							public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
								if (superName != null) {
									if (className.equals(name) && !((Remapper) (remapper_n2i.getRemapper())).map(superName).equals(superName)) {
										project.getLogger().debug(className + " inherits from vanilla class!");
										ret.add(Pair.of(superName, true));
									} else if (className.equals(name)) {
										project.getLogger().debug(className + " does not inherit from vanilla class. "
												+ "But maybe " + superName + " does?");
										ret.add(Pair.of(superName, false));
									}
								}

								super.visit(version, access, name, signature, superName, interfaces);
							}
						};
				reader.accept(classVisitor, 0);
				return writer.toByteArray();
			}
		};
	}

	private static Set<String> findMixins(File output, boolean onlyWithoutRefmap) {
		// first, identify all of the mixin files
		Set<String> mixinFilename = new HashSet<>();
		// TODO: this is a lovely hack
		ZipUtil.iterate(output, (stream, entry) -> {
			if (!entry.isDirectory() && entry.getName().endsWith(".json") && !entry.getName().contains("/") && !entry.getName().contains("\\")) {
				// JSON file in root directory
				try (InputStreamReader inputStreamReader = new InputStreamReader(stream)) {
					JsonObject json = LoomGradlePlugin.GSON.fromJson(inputStreamReader, JsonObject.class);

					if (json != null) {
						boolean hasMixins = json.has("mixins") && json.get("mixins").isJsonArray();
						boolean hasClient = json.has("client") && json.get("client").isJsonArray();
						boolean hasServer = json.has("server") && json.get("server").isJsonArray();

						if (json.has("package") && (hasMixins || hasClient || hasServer)) {
							if (!onlyWithoutRefmap || !json.has("refmap") || !json.has("minVersion")) {
								mixinFilename.add(entry.getName());
							}
						}
					}
				} catch (Exception ignored) {
					// ...
				}
			}
		});
		return mixinFilename;
	}
}
