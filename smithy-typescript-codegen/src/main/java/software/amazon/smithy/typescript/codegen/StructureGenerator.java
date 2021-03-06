/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.typescript.codegen;

import java.util.stream.Collectors;
import java.util.stream.Stream;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.codegen.core.SymbolReference;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.ErrorTrait;
import software.amazon.smithy.typescript.codegen.integration.HttpProtocolGeneratorUtils;

/**
 * Generates normal structures and error structures.
 */
final class StructureGenerator implements Runnable {

    private final Model model;
    private final SymbolProvider symbolProvider;
    private final TypeScriptWriter writer;
    private final StructureShape shape;

    StructureGenerator(Model model, SymbolProvider symbolProvider, TypeScriptWriter writer, StructureShape shape) {
        this.model = model;
        this.symbolProvider = symbolProvider;
        this.writer = writer;
        this.shape = shape;
    }

    @Override
    public void run() {
        if (shape.hasTrait(ErrorTrait.class)) {
            renderErrorStructure();
        } else {
            renderNonErrorStructure();
        }
    }

    /**
     * Renders a normal, non-error structure.
     *
     * <p>For example, given the following Smithy model:
     *
     * <pre>{@code
     * namespace smithy.example
     *
     * structure Person {
     *     @required
     *     name: String,
     *     age: Integer,
     * }
     * }</pre>
     *
     * <p>The following TypeScript is rendered:
     *
     * <pre>{@code
     * import { isa as __isa } from "@aws-sdk/smithy-client";
     *
     * export interface Person {
     *   __type?: "Person";
     *   name: string | undefined;
     *   age?: number | null;
     * }
     *
     * export namespace Person {
     *   export const isa = (o: any): o is Person => __isa(o, "Person");
     * }
     * }</pre>
     */
    private void renderNonErrorStructure() {
        Symbol symbol = symbolProvider.toSymbol(shape);
        writer.writeShapeDocs(shape);

        // Find symbol references with the "extends" property.
        String extendsFrom = symbol.getReferences().stream()
                .filter(ref -> ref.getProperty(SymbolVisitor.IMPLEMENTS_INTERFACE_PROPERTY).isPresent())
                .map(SymbolReference::getAlias)
                .collect(Collectors.joining(", "));

        if (extendsFrom.isEmpty()) {
            writer.openBlock("export interface $L {", symbol.getName());
        } else {
            writer.openBlock("export interface $L extends $L {", symbol.getName(), extendsFrom);
        }

        writer.write("__type?: $S;", shape.getId().getName());
        StructuredMemberWriter config = new StructuredMemberWriter(
                model, symbolProvider, shape.getAllMembers().values());
        config.writeMembers(writer, shape);
        writer.closeBlock("}");
        writer.write("");
        renderStructureNamespace();
    }

    /**
     * Error structures generate interfaces that extend from SmithyException
     * and add the appropriate fault property.
     *
     * <p>Given the following Smithy structure:
     *
     * <pre>{@code
     * namespace smithy.example
     *
     * @error("client")
     * structure NoSuchResource {
     *     @required
     *     resourceType: String
     * }
     * }</pre>
     *
     * <p>The following TypeScript is generated:
     *
     * <pre>{@code
     * import {
     *     SmithyException as __SmithyException,
     *     isa as __isa
     * } from "@aws-sdk/smithy-client";
     *
     * export interface NoSuchResource extends __SmithyException, $MetadataBearer {
     *   name: "NoSuchResource";
     *   $fault: "client";
     *   resourceType: string | undefined;
     * }
     *
     * export namespace NoSuchResource {
     *   export const isa = (o: any): o is NoSuchResource => __isa(o, "NoSuchResource");
     * }
     * }</pre>
     */
    private void renderErrorStructure() {
        ErrorTrait errorTrait = shape.getTrait(ErrorTrait.class).orElseThrow(IllegalStateException::new);
        Symbol symbol = symbolProvider.toSymbol(shape);
        writer.writeShapeDocs(shape);

        // Find symbol references with the "extends" property, and add SmithyException.
        writer.addImport("SmithyException", "__SmithyException", "@aws-sdk/smithy-client");
        String extendsFrom = Stream.concat(
                Stream.of("__SmithyException"),
                symbol.getReferences().stream()
                        .filter(ref -> ref.getProperty(SymbolVisitor.IMPLEMENTS_INTERFACE_PROPERTY).isPresent())
                        .map(SymbolReference::getAlias)
                ).collect(Collectors.joining(", "));

        writer.openBlock("export interface $L extends $L {", symbol.getName(), extendsFrom);
        writer.write("name: $S;", shape.getId().getName());
        writer.write("$$fault: $S;", errorTrait.getValue());
        HttpProtocolGeneratorUtils.writeRetryableTrait(writer, shape, ";");
        StructuredMemberWriter structuredMemberWriter = new StructuredMemberWriter(
                model, symbolProvider, shape.getAllMembers().values());
        structuredMemberWriter.writeMembers(writer, shape);
        writer.closeBlock("}"); // interface
        writer.write("");
        renderStructureNamespace();
    }

    private void renderStructureNamespace() {
        writer.addImport("isa", "__isa", "@aws-sdk/smithy-client");
        writer.addImport("SENSITIVE_STRING", "SENSITIVE_STRING", "@aws-sdk/smithy-client");
        Symbol symbol = symbolProvider.toSymbol(shape);
        writer.openBlock("export namespace $L {", "}", symbol.getName(), () -> {
            String objectParam = "obj";
            writer.openBlock("export const filterSensitiveLog = ($L: $L): any => ({", "})",
                objectParam, symbol.getName(),
                () -> {
                    StructuredMemberWriter structuredMemberWriter = new StructuredMemberWriter(
                        model, symbolProvider, shape.getAllMembers().values());
                    structuredMemberWriter.writeFilterSensitiveLog(writer, objectParam);
                }
            );
            writer.write("export const isa = (o: any): o is $L => __isa(o, $S);",
                symbol.getName(), shape.getId().getName()
            );
        });
    }
}
