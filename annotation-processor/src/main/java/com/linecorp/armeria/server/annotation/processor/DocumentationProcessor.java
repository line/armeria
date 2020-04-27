/*
 * Copyright 2020 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.annotation.processor;

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

import com.linecorp.armeria.server.annotation.Description;

/**
 * Processor that creates a properties file based on the JavaDoc/KDoc description of parameters for interfaces.
 * This file can be later used if the {@link Description} annotation does not exist for parameters or methods.
 */
@SupportedAnnotationTypes({
        "com.linecorp.armeria.server.annotation.Post",
        "com.linecorp.armeria.server.annotation.Get",
        "com.linecorp.armeria.server.annotation.Put",
        "com.linecorp.armeria.server.annotation.Delete",
        "com.linecorp.armeria.server.annotation.Head",
        "com.linecorp.armeria.server.annotation.Options",
        "com.linecorp.armeria.server.annotation.Patch",
})
public class DocumentationProcessor extends AbstractProcessor {
    private final Map<String, Properties> propertiesMap = new HashMap<>();

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (annotations == null || annotations.isEmpty()) {
            return false;
        }
        annotations.forEach(annotation -> processAnnotation(annotation, roundEnv));
        propertiesMap.forEach((className, properties) -> {
            try {
                writeProperties(className, properties);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        return false;
    }

    private Properties readProperties(String className) throws IOException {
        if (propertiesMap.containsKey(className)) {
            return propertiesMap.get(className);
        }
        final FileObject resource = processingEnv
                .getFiler()
                .getResource(StandardLocation.CLASS_OUTPUT,
                             "",
                             getFileName(className));
        final Properties properties = new Properties();
        if (resource.getLastModified() == 0L) {
            // returns 0 if file does not exist
            propertiesMap.put(className, properties);
            return properties;
        }
        try (Reader reader = resource.openReader(false)) {
            properties.load(reader);
            return properties;
        }
    }

    private void writeProperties(String className, Properties properties) throws IOException {
        if (properties.size() == 0) {
            return;
        }
        final FileObject resource = processingEnv.getFiler().createResource(
                StandardLocation.CLASS_OUTPUT,
                "",
                getFileName(className));
        try (Writer writer = resource.openWriter()) {
            properties.store(writer, "Generated list of parameter description of REST interfaces.");
        }
    }

    private void processAnnotation(TypeElement annotationElement, RoundEnvironment roundEnv) {
        roundEnv.getElementsAnnotatedWith(annotationElement)
                .stream()
                .filter(element -> element.getKind() == ElementKind.METHOD)
                // Element is always ExecutableElement because it is a method.
                .forEachOrdered(element -> {
                    try {
                        processMethod((ExecutableElement) element);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
    }

    private void processMethod(ExecutableElement method) throws IOException {
        final String className = ((TypeElement) method.getEnclosingElement()).getQualifiedName().toString();
        final Properties properties = readProperties(className);
        final String docComment = processingEnv.getElementUtils().getDocComment(method);
        if (docComment == null || !docComment.contains("@param")) {
            return;
        }
        final List<List<String>> lines = Arrays.stream(docComment.split("\\R"))
                                               .filter(line -> !line.trim().isEmpty())
                                               .map(line -> Arrays.stream(line.split("\\s"))
                                                                  .filter(word -> !word.trim().isEmpty())
                                                                  .collect(toImmutableList()))
                                               .collect(toImmutableList());
        method.getParameters().forEach(param -> {
            final StringBuilder stringBuilder = new StringBuilder();
            JavaDocParserState state = JavaDocParserState.SEARCHING;
            for (List<String> line : lines) {
                final List<String> subLine;
                if ((line.size() < 3 ||
                     !"@param".equals(line.get(0)) ||
                     !param.getSimpleName().toString().equals(line.get(1))) &&
                    state == JavaDocParserState.SEARCHING) {
                    continue;
                } else if (state == JavaDocParserState.IN_DESCRIPTION &&
                           line.size() > 0 &&
                           "@param".equals(line.get(0))) {
                    break;
                } else if (state == JavaDocParserState.SEARCHING) {
                    subLine = line.subList(2, line.size());
                    state = JavaDocParserState.IN_DESCRIPTION;
                } else {
                    subLine = line;
                }
                for (String word : subLine) {
                    stringBuilder.append(word);
                    stringBuilder.append(' ');
                }
            }
            setProperty(properties, method, param, stringBuilder.toString().trim());
        });
    }

    private void setProperty(Properties properties,
                             ExecutableElement method,
                             VariableElement parameter,
                             String description) {
        final String methodName = method.getSimpleName().toString();
        final String parameterName = parameter.getSimpleName().toString();
        properties.setProperty(methodName + "." + parameterName, description);
    }

    /**
     * Creates the file name used in the rest api documentation properties files.
     * @param className The class name used for generating the file name.
     * @return The used file name.
     */
    public static String getFileName(String className) {
        return "com.linecorp.armeria.docstrings.annotated." + className + ".properties";
    }

    private enum JavaDocParserState {
        IN_DESCRIPTION, SEARCHING
    }
}
