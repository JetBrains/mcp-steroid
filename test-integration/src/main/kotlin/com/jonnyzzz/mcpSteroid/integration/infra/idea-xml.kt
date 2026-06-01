/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import org.jdom2.Document
import org.jdom2.Element
import org.jdom2.output.Format
import org.jdom2.output.XMLOutputter

/**
 * Shared jdom2 helpers for the IntelliJ `.idea` / config XML we read, patch and generate from
 * infra code (jdk.table.xml generator, misc.xml / gradle.xml patchers). Centralised so every
 * place serialises with the same format and finds-or-creates elements the same way.
 */

/** Serialize using IntelliJ-style formatting: 2-space indent, `\n` line separator. */
internal fun Document.toIdeaXml(): String =
    XMLOutputter(Format.getPrettyFormat().setLineSeparator("\n")).outputString(this)

/** The `<component name="…">` child, created (and appended) when absent. */
internal fun Element.ensureComponent(name: String): Element =
    children.firstOrNull { it.name == "component" && it.getAttributeValue("name") == name }
        ?: Element("component").setAttribute("name", name).also { addContent(it) }

/** The `<option name="…">` child if present, else null. */
internal fun Element.findOption(name: String): Element? =
    children.firstOrNull { it.name == "option" && it.getAttributeValue("name") == name }

/** The `<option name="…">` child, created (and appended) when absent. */
internal fun Element.ensureOption(name: String): Element =
    findOption(name) ?: Element("option").setAttribute("name", name).also { addContent(it) }

/** The first `<name>` child element, created (and appended) when absent. */
internal fun Element.ensureChild(name: String): Element =
    children.firstOrNull { it.name == name } ?: Element(name).also { addContent(it) }
