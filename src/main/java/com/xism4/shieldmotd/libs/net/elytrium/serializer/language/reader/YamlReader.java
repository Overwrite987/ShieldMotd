/*
 * Copyright (C) 2023 Elytrium
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.xism4.shieldmotd.libs.net.elytrium.serializer.language.reader;

import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.BufferedReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.xism4.shieldmotd.libs.net.elytrium.serializer.SerializerConfig;
import com.xism4.shieldmotd.libs.net.elytrium.serializer.annotations.FallbackNodeNames;
import com.xism4.shieldmotd.libs.net.elytrium.serializer.annotations.Final;
import com.xism4.shieldmotd.libs.net.elytrium.serializer.annotations.OverrideNameStyle;
import com.xism4.shieldmotd.libs.net.elytrium.serializer.annotations.RegisterPlaceholders;
import com.xism4.shieldmotd.libs.net.elytrium.serializer.annotations.Transient;
import com.xism4.shieldmotd.libs.net.elytrium.serializer.custom.ClassSerializer;
import com.xism4.shieldmotd.libs.net.elytrium.serializer.language.object.YamlSerializable;
import com.xism4.shieldmotd.libs.net.elytrium.serializer.language.writer.YamlWriter;
import com.xism4.shieldmotd.libs.net.elytrium.serializer.placeholders.DefaultPlaceholderReplacer;
import com.xism4.shieldmotd.libs.net.elytrium.serializer.placeholders.PlaceholderReplacer;
import com.xism4.shieldmotd.libs.net.elytrium.serializer.placeholders.Placeholders;
import com.xism4.shieldmotd.libs.net.elytrium.serializer.utils.GenericUtils;

@SuppressWarnings({"StringRepeatCanBeUsed"}) // Ignore modern methods, because we support up to Java 8.
public class YamlReader extends AbstractReader {

  private static final Logger LOGGER = Logger.getLogger(YamlReader.class.getName());

  private final YamlSerializable yamlSerializable;

  private int currentIndent;
  private int seekIndent;
  private int nodeIndent;
  private int newLineIndent;
  private boolean tempRestoreNewLine;
  private boolean bracketOpened;
  private boolean startOfFile = true;

  private StringBuilder spacesBuffer;

  public YamlReader(BufferedReader reader, SerializerConfig config) {
    super(config, reader);
    this.yamlSerializable = null;
  }

  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public YamlReader(BufferedReader reader, SerializerConfig config, YamlSerializable serializable) {
    super(config, reader);
    this.yamlSerializable = serializable;
  }

  public YamlReader(BufferedReader reader) {
    super(reader);
    this.yamlSerializable = null;
  }

  @Override
  @SuppressFBWarnings("SA_FIELD_SELF_COMPARISON")
  public void readSerializableObject(@Nullable Field owner, Object holder, Class<?> clazz) {
    synchronized (this) {
      this.unsetTempRestoreNewLine();
      this.readBeginSerializableObject(owner);
      Field[] nodes = clazz.getDeclaredFields();
      if (nodes.length != 0) {
        try {
          // Register initial values and make nodeName->field map.
          Map<String, Field> nodeFieldMap = new HashMap<>();
          for (Field node : nodes) {
            try {
              node.setAccessible(true);
            } catch (Exception e) {
              continue;
            }

            Object value = node.get(holder);
            this.removePlaceholders(value);
            this.updatePlaceholders(node, value); // Updating placeholders here in case if on reload field was written and not read yet.

            OverrideNameStyle overrideNameStyle = node.getAnnotation(OverrideNameStyle.class);
            if (overrideNameStyle == null) {
              overrideNameStyle = node.getType().getAnnotation(OverrideNameStyle.class);
            }

            nodeFieldMap.put(overrideNameStyle == null ? this.config.toNodeName(node.getName()) : this.config.toNodeName(node.getName(), overrideNameStyle.field(), overrideNameStyle.node()), node);

            FallbackNodeNames fallbackNodeNames = node.getAnnotation(FallbackNodeNames.class);
            if (fallbackNodeNames != null) {
              for (String fallbackNodeName : fallbackNodeNames.value()) {
                nodeFieldMap.put(fallbackNodeName, node);
              }
            }
          }

          int correctIndent = this.currentIndent;
          String nodeName;
          while (correctIndent == this.currentIndent && (nodeName = this.readNodeName(owner)) != null) {
            try {
              Field node = nodeFieldMap.get(nodeName);
              if (node == null) {
                this.skipGuessingType(owner);
                this.setBackupPreferred();
                if (this.config.isLogMissingFields()) {
                  YamlReader.LOGGER.log(Level.WARNING, "Skipping node " + nodeName + " due to missing field");
                }
              } else {
                int modifiers = node.getModifiers();
                if (!Modifier.isStatic(modifiers) && !Modifier.isFinal(modifiers) && !Modifier.isTransient(modifiers)
                    && node.getAnnotation(Final.class) == null && node.getType().getAnnotation(Final.class) == null
                    && node.getAnnotation(Transient.class) == null && node.getType().getAnnotation(Transient.class) == null) {
                  this.removePlaceholders(node.get(holder));
                  this.updatePlaceholders(node, this.readNode(holder, node));
                } else {
                  this.skipNode(node);
                }
              }
            } catch (ReflectiveOperationException e) {
              this.skipGuessingType(owner);
              this.setBackupPreferred();
              if (this.config.isLogMissingFields()) {
                YamlReader.LOGGER.log(Level.WARNING, "Skipping node " + nodeName + " due to exception caught", e);
              }
            }

            this.readSerializableObjectEntryJoin(owner);

            if (this.readEndSerializableObject(owner)) {
              return;
            }

            if (correctIndent != this.currentIndent) {
              while (true) {
                if (!this.skipComments(owner, this.readRaw(), true)) {
                  break;
                }
              }
            }
          }
        } catch (ReflectiveOperationException e) {
          throw new RuntimeException(e);
        }
      }

      this.readEndSerializableObject(owner);
    }
  }

  private void removePlaceholders(Object value) throws ReflectiveOperationException {
    Placeholders.removePlaceholders(value);
    if (value instanceof Collection<?> collection) {
      for (Object entry : collection) {
        Placeholders.removePlaceholders(entry);
      }
    }
  }

  private void updatePlaceholders(Field node, Object value) throws ReflectiveOperationException {
    RegisterPlaceholders placeholders = node.getAnnotation(RegisterPlaceholders.class);
    if (placeholders == null) {
      placeholders = node.getType().getAnnotation(RegisterPlaceholders.class);
    }

    if (placeholders != null) {
      PlaceholderReplacer<?, ?> replacer = null;
      if (placeholders.replacer() == DefaultPlaceholderReplacer.class) {
        replacer = value instanceof Collection<?>
            ? this.config.getRegisteredReplacer((Class<?>) GenericUtils.getParameterType(Collection.class, node.getGenericType(), 0))
            : this.config.getRegisteredReplacer(node.getType());
      }

      if (replacer == null) {
        replacer = this.config.getAndCacheReplacer(placeholders.replacer());
      }

      if (this.config.isRegisterPlaceholdersForCollectionEntries() && value instanceof Collection<?> collection) {
        for (Object entry : collection) {
          Placeholders.addPlaceholders(entry, replacer, placeholders.wrapWithBraces(), placeholders.value());
        }
      }

      Placeholders.addPlaceholders(value, replacer, placeholders.wrapWithBraces(), placeholders.value());
    }
  }

  @Override
  public String readNodeName(@Nullable Field owner) {
    synchronized (this) {
      char marker = this.readRawIgnoreEmptyAndNewLines();
      return marker == '\0' ? null : this.readNodeNameFromMarker(owner, marker);
    }
  }

  @Override
  public void readBeginSerializableObject(@Nullable Field owner) {
    synchronized (this) {
      this.skipChar('{');
    }
  }

  @Override
  public void readSerializableObjectEntryJoin(@Nullable Field owner) {
    synchronized (this) {
      this.skipChar(',');
    }
  }

  @Override
  public boolean readEndSerializableObject(@Nullable Field owner) {
    synchronized (this) {
      return this.skipChar('}');
    }
  }

  @Override
  public Object readGuessingType(@Nullable Field owner) {
    synchronized (this) {
      return this.readGuessingTypeFromMarker(owner, this.readRawIgnoreEmpty());
    }
  }

  @Override
  public <C extends Map<Object, Object>> C readMap(@Nullable Field owner, C result, Type keyType, Type valueType) {
    boolean startOfFile = this.startOfFile;
    synchronized (this) {
      char marker = this.readRawIgnoreEmpty();
      if (startOfFile) {
        this.setTempRestoreNewLine();
      }

      return this.readMapFromMarker(owner, result, keyType, valueType, marker);
    }
  }

  @Override
  public <C extends Collection<Object>> C readCollection(@Nullable Field owner, C result, Type type) {
    synchronized (this) {
      return this.readCollectionFromMarker(owner, result, type, this.readRawIgnoreEmpty());
    }
  }

  @Override
  public Character readCharacter(@Nullable Field owner) {
    synchronized (this) {
      return this.readCharacterFromMarker(owner, this.readRawIgnoreEmpty());
    }
  }

  @Override
  public Boolean readBoolean(@Nullable Field owner) {
    synchronized (this) {
      return Boolean.valueOf(this.readString(owner));
    }
  }

  @Override
  public Double readDouble(@Nullable Field owner) throws NumberFormatException {
    synchronized (this) {
      String value = this.readString(owner);
      return value == null ? null : Double.valueOf(value);
    }
  }

  @Override
  public Long readLong(@Nullable Field owner) throws NumberFormatException {
    synchronized (this) {
      String value = this.readString(owner);
      return value == null ? null : Long.valueOf(value);
    }
  }

  @Override
  public String readString(@Nullable Field owner) {
    synchronized (this) {
      return this.readStringFromMarker(owner, this.readRawIgnoreEmpty(), false);
    }
  }

  private String readNodeNameFromMarker(@Nullable Field owner, char marker) {
    synchronized (this) {
      this.nodeIndent = this.currentIndent;
    }

    while (true) {
      if (this.skipComments(owner, marker, false) || this.skipComments(owner, this.readRawIgnoreEmpty(), true)) {
        marker = this.readRaw();
      } else {
        break;
      }
    }

    String nodeName;
    if (marker == '"' || marker == '\'') {
      nodeName = this.readStringFromMarker(owner, marker, true);
    } else {
      StringBuilder result = new StringBuilder(12);
      while (true) {
        while (marker != ':') {
          if (this.isEndMarker(marker)) {
            throw new IllegalStateException("Got a new line in node name: " + result);
          }

          result.append(marker);
          marker = this.readRaw();
        }

        marker = this.readRaw();
        if (Character.isWhitespace(marker)) {
          this.setReuseBuffer();
          break;
        }

        result.append(':');
      }

      nodeName = result.toString();
    }

    return nodeName;
  }

  private Object readGuessingTypeFromMarker(@Nullable Field owner, char marker) {
    return switch (marker) {
      case AbstractReader.NEW_LINE -> {
        char nextMarker = this.readRawIgnoreEmpty();
        this.setReuseBuffer();
        yield nextMarker == '-' ? this.readListFromMarker(owner, marker) : this.readMapFromMarker(owner, marker);
      }
      case '-' -> {
        this.setReuseBuffer();
        this.setSeek();
        String string = this.readString(owner);
        if (string != null) {
          try {
            try {
              yield Long.parseLong(string);
            } catch (NumberFormatException e) {
              yield Double.parseDouble(string);
            }
          } catch (NumberFormatException ignored) {
            // Exception ignored as the string doesn't contain negative number here, but contains a list.
          }
        }

        this.unsetSeek();
        yield this.readListFromMarker(owner, AbstractReader.NEW_LINE);
      }
      case '[' -> this.readListFromMarker(owner, marker);
      case '{' -> this.readMapFromMarker(owner, marker);
      case '"', '\'', '>', '|' -> this.readStringFromMarker(owner, marker, false);
      default -> {
        if (this.isNullSkippedFromMarker(marker)) {
          yield null;
        }

        this.setSeekFromMarker(marker);
        String string = this.readStringFromMarker(owner, marker, false);
        if (string == null) {
          yield null;
        }

        if (string.endsWith(":") || string.endsWith(": ") || string.contains(": ")) {
          this.unsetSeek();
          this.unsetTempRestoreNewLine();
          yield this.readMapFromMarker(owner, AbstractReader.NEW_LINE);
        } else {
          this.clearSeek();
          try {
            try {
              yield Long.parseLong(string);
            } catch (NumberFormatException e) {
              yield Double.parseDouble(string);
            }
          } catch (NumberFormatException e) {
            yield string;
          }
        }
      }
    };
  }

  private Map<Object, Object> readMapFromMarker(@Nullable Field owner, char marker) {
    return this.readMapFromMarker(owner, new LinkedHashMap<>(), Object.class, Object.class, marker);
  }

  @SuppressWarnings("DuplicatedCode")
  @SuppressFBWarnings("SA_FIELD_SELF_COMPARISON")
  private <C extends Map<Object, Object>> C readMapFromMarker(@Nullable Field owner, C result, Type keyType, Type valueType, char marker) {
    if (this.skipComments(owner, marker, false)) { // TODO сделать чтобы map:#comm не читалось
      marker = AbstractReader.NEW_LINE;
    }

    char nextMarker;
    if (this.tempRestoreNewLine) {
      nextMarker = marker;
      marker = AbstractReader.NEW_LINE;
      this.unsetTempRestoreNewLine();
    } else {
      nextMarker = this.readRawIgnoreEmptyAndNewLines();
    }

    boolean previousBracketOpened = this.bracketOpened;
    switch (marker) {
      case '{' -> {
        this.bracketOpened = true;
        while (nextMarker != '}') {
          this.readMapEntry(owner, keyType, valueType, this.readNodeNameFromMarker(null, nextMarker), result);
          nextMarker = this.readRawIgnoreEmptyAndNewLines();
        }

        this.bracketOpened = previousBracketOpened;
      }
      case AbstractReader.NEW_LINE -> {
        this.bracketOpened = false;
        int correctIndent = this.currentIndent;
        while (nextMarker != '\0' && correctIndent == this.currentIndent) {
          this.readMapEntry(owner, keyType, valueType, this.readNodeNameFromMarker(null, nextMarker), result);
          nextMarker = this.readRawIgnoreEmptyAndNewLines();
          if (this.skipComments(owner, nextMarker, false)) {
            nextMarker = this.readRawIgnoreEmptyAndNewLines();
          }
        }

        this.setReuseBuffer();
        this.bracketOpened = previousBracketOpened;
      }
      default -> {
        if (this.isNullSkippedFromMarker(marker)) {
          return null;
        } else {
          throw new IllegalStateException("Got unknown marker when reading map: " + marker);
        }
      }
    }

    return result;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void readMapEntry(@Nullable Field owner, Type keyType, Type valueType, String nodeName, Map<Object, Object> result) {
    Object key;
    if (keyType == Object.class) {
      try {
        try {
          key = Long.parseLong(nodeName);
        } catch (NumberFormatException e) {
          key = Double.parseDouble(nodeName);
        }
      } catch (NumberFormatException e) {
        key = nodeName;
      }
    } else if (keyType instanceof Class<?> keyClazz) {
      if (String.class.isAssignableFrom(keyClazz)) {
        key = nodeName;
      } else if (Character.class.isAssignableFrom(keyClazz) || char.class.isAssignableFrom(keyClazz)) {
        if (nodeName.isEmpty()) {
          throw new IllegalStateException("Character can't be null!");
        } else {
          key = nodeName.charAt(0);
        }
      } else if (keyClazz.isEnum()) {
        key = Enum.valueOf((Class<? extends Enum>) keyClazz, nodeName);
      } else if (Boolean.class.isAssignableFrom(keyClazz) || boolean.class.isAssignableFrom(keyClazz)) {
        key = Boolean.valueOf(nodeName);
      } else if (Number.class.isAssignableFrom(keyClazz) || keyClazz.isPrimitive()) {
        if (Double.class.isAssignableFrom(keyClazz) || double.class.isAssignableFrom(keyClazz)) {
          key = Double.valueOf(nodeName);
        } else if (Float.class.isAssignableFrom(keyClazz) || float.class.isAssignableFrom(keyClazz)) {
          key = Float.valueOf(nodeName);
        } else if (Long.class.isAssignableFrom(keyClazz) || long.class.isAssignableFrom(keyClazz)) {
          key = Long.valueOf(nodeName);
        } else if (Integer.class.isAssignableFrom(keyClazz) || int.class.isAssignableFrom(keyClazz)) {
          key = Integer.valueOf(nodeName);
        } else if (Short.class.isAssignableFrom(keyClazz) || short.class.isAssignableFrom(keyClazz)) {
          key = Short.valueOf(nodeName);
        } else if (Byte.class.isAssignableFrom(keyClazz) || byte.class.isAssignableFrom(keyClazz)) {
          key = Byte.valueOf(nodeName);
        } else {
          throw new IllegalStateException("Number " + keyClazz + " for map key are not supported yet!");
        }
      } else {
        Deque<ClassSerializer<?, Object>> serializerStack = new ArrayDeque<>(Math.min(16, this.config.getRegisteredSerializers() + 1/*See AbstractReader#readNode*/));
        Type type = this.fillSerializerStack(serializerStack, keyClazz);
        if (serializerStack.isEmpty()) {
          throw new IllegalStateException("Class " + keyClazz + " for map key are not supported yet!");
        }

        if (!(type instanceof Class<?>) || Map.class.isAssignableFrom((Class<?>) type) || Collection.class.isAssignableFrom((Class<?>) type)) {
          throw new IllegalStateException("Class " + type + " for map key is not supported!");
        }

        key = this.readAndDeserializeByType(owner, null, type, serializerStack);
      }
    } else {
      throw new IllegalStateException("Type " + keyType + " for map key are not supported yet!");
    }

    result.put(key, this.readByType0(owner, valueType));
  }

  private List<Object> readListFromMarker(@Nullable Field owner, char marker) {
    return this.readCollectionFromMarker(owner, new ArrayList<>(), Object.class, marker);
  }

  @SuppressFBWarnings("SA_FIELD_SELF_COMPARISON")
  private <C extends Collection<Object>> C readCollectionFromMarker(@Nullable Field owner, C result, Type type, char marker) {
    if (this.skipComments(owner, marker, false)) { // TODO сделать чтобы collection:#comm не читалось
      marker = AbstractReader.NEW_LINE;
    }

    this.unsetTempRestoreNewLine();
    switch (marker) {
      case '[': {
        char nextMarker = this.readRawIgnoreEmptyAndNewLines();
        while (nextMarker != ']') {
          result.add(this.readByType0(owner, type));
          nextMarker = this.readRawIgnoreEmptyAndNewLines();
        }

        break;
      }
      case AbstractReader.NEW_LINE: {
        this.skipComments(owner, this.readRawIgnoreEmpty(), true);
        char nextMarker = this.readRawIgnoreEmpty();
        if (nextMarker != '-') {
          throw new IllegalStateException("Got unknown marker when reading list: " + nextMarker);
        }
      } // Got '-' after newline, fall through here.
      case '-': {
        this.nodeIndent = this.currentIndent;
        char nextMarker = '-';
        int correctIndent = this.currentIndent;
        while (nextMarker == '-' && correctIndent == this.currentIndent) {
          this.setTempRestoreNewLine();
          result.add(this.readByType0(null, type));
          this.unsetTempRestoreNewLine();
          nextMarker = this.readRawIgnoreEmptyAndNewLines();
          if (this.skipComments(owner, nextMarker, false)) {
            nextMarker = this.readRawIgnoreEmptyAndNewLines();
          }
        }

        this.setReuseBuffer();
        break;
      }
      default: {
        if (this.isNullSkippedFromMarker(marker)) {
          return null;
        } else {
          throw new IllegalStateException("Got unknown marker when reading list: " + marker);
        }
      }
    }

    return result;
  }

  private Object readByType0(@Nullable Field owner, Type type) {
    Object result = this.readByType(owner, type);
    return type == Integer.class || type == int.class ? Integer.valueOf(((Long) result).intValue())
        : type == Short.class || type == short.class ? Short.valueOf(((Long) result).shortValue())
        : type == Byte.class || type == byte.class ? Byte.valueOf(((Long) result).byteValue())
        : type == Float.class || type == float.class ? Float.valueOf(((Double) result).floatValue())
        : result; // Long || Double
  }

  private Character readCharacterFromMarker(@Nullable Field owner, char marker) {
    Character result = null;
    switch (marker) {
      case '"' -> {
        while ((marker = this.readRaw()) != '"') {
          if (result == null) {
            if (marker == '\\') {
              int character = this.readEscapedCharacter();
              if (Character.isBmpCodePoint(character)) {
                result = (char) character;
              } else {
                throw new IllegalStateException("Supplementary char cannot be stored in Character");
              }
            } else {
              result = marker;
            }
          } else if (marker == '\\') {
            this.readRaw(); // To ensure the reading doesn't stop at \"
          }
        }
      }
      case '\'' -> {
        while (true) {
          marker = this.readRaw();
          if (marker == '\'') {
            if (this.readRaw() == '\'') { // 'text1 ''text2'' text3' reads as "text 'text2' text3".
              if (result == null) {
                result = '\'';
              }
            } else {
              this.setReuseBuffer();
              break;
            }
          } else if (result == null) {
            result = marker;
          }
        }
      }
      default -> {
        if (this.isNullSkippedFromMarker(marker)) {
          return null;
        }

        while (!this.isEndMarker(marker) && (marker != ',' || this.bracketOpened) && !this.skipComments(owner, marker, false)) {
          if (result == null) { // Set the result only after we ensure we not at end of line/field.
            result = marker;
          }

          marker = this.readRaw();
        }
      }
    }

    return result;
  }

  private int readEscapedCharacter() {
    synchronized (this) {
      char marker;
      return switch (marker = this.readRaw()) {
        case '0' -> '\0';
        case 'a' -> '\u0007';
        case 'b' -> '\b';
        case 't' -> '\t';
        case 'n' -> '\n';
        case 'v' -> '\u000B';
        case 'f' -> '\f';
        case 'r' -> '\r';
        case 'e' -> '\u001B';
        case ' ' -> ' ';
        case '"' -> '\"';
        case '\\' -> '\\';
        case 'N' -> '\u0085';
        case '_' -> '\u00A0';
        case 'L' -> '\u2028';
        case 'P' -> '\u2029';
        case 'x' -> this.readHexChar(2);
        case 'u' -> this.readHexChar(4);
        case 'U' -> this.readHexChar(8);
        default -> throw new IllegalStateException("Invalid escape character: \\" + marker);
      };
    }
  }

  private int readHexChar(int size) {
    StringBuilder hex = new StringBuilder(size);
    for (int i = 0; i < size; ++i) {
      char character = this.readRaw();
      if (this.isEndMarker(character)) {
        throw new IllegalStateException("Got new line while reading hex char");
      }

      hex.append(character);
    }

    return Integer.parseInt(hex.toString(), 16);
  }

  private String readStringFromMarker(@Nullable Field owner, char marker, boolean nodeName) {
    StringBuilder result = new StringBuilder();
    switch (marker) {
      case '"' -> {
        if (owner != null && !nodeName && this.yamlSerializable != null) {
          this.yamlSerializable.saveStringStyle(owner, YamlWriter.StringStyle.DOUBLE_QUOTED);
        }

        int newLineCount = 0;
        while ((marker = this.readRaw()) != '"') {
          if (this.isEndMarker(marker)) {
            if (nodeName) {
              throw new IllegalStateException("Got a new line in node name: " + result);
            }

            this.readRawIgnoreEmpty();
            this.setReuseBuffer();
            ++newLineCount;
          } else {
            if (newLineCount == 1) {
              result.append(' ');
            } else {
              for (int i = 1; i < newLineCount; ++i) {
                result.append(AbstractReader.NEW_LINE);
              }
            }

            newLineCount = 0;

            if (marker == '\\') {
              // Inlined Character.toChars()
              int character = this.readEscapedCharacter();
              if (Character.isBmpCodePoint(character)) {
                result.append((char) character);
              } else if (Character.isValidCodePoint(character)) {
                result.append(Character.highSurrogate(character));
                result.append(Character.lowSurrogate(character));
              } else {
                throw new IllegalArgumentException(String.format("Not a valid Unicode code point: 0x%X", character));
              }
            } else {
              result.append(marker);
            }
          }
        }

        if (nodeName) {
          marker = this.readRawIgnoreEmpty();
          if (marker != ':') {
            throw new IllegalStateException("Got illegal marker when reading node name: " + marker + " for " + result);
          }
        }
      }
      case '\'' -> {
        if (owner != null && !nodeName && this.yamlSerializable != null) {
          this.yamlSerializable.saveStringStyle(owner, YamlWriter.StringStyle.SINGLE_QUOTED);
        }

        int newLineCount = 0;
        while (true) {
          marker = this.readRaw();
          if (this.isEndMarker(marker)) {
            if (nodeName) {
              throw new IllegalStateException("Got a new line in node name: " + result);
            }

            this.readRawIgnoreEmpty();
            this.setReuseBuffer();
            ++newLineCount;
          } else {
            if (newLineCount == 1) {
              result.append(' ');
            } else {
              for (int i = 1; i < newLineCount; ++i) {
                result.append(AbstractReader.NEW_LINE);
              }
            }

            newLineCount = 0;

            if (marker == '\'') {
              if (this.readRaw() == '\'') { // 'text1 ''text2'' text3' reads as "text 'text2' text3".
                result.append('\'');
              } else {
                this.setReuseBuffer();
                break;
              }
            } else {
              result.append(marker);
            }
          }
        }

        if (nodeName) {
          marker = this.readRawIgnoreEmpty();
          if (marker != ':') {
            throw new IllegalStateException("Got illegal marker when reading node name: " + marker + " for " + result);
          }
        }
      }
      default -> {
        if (marker == '#') {
          this.skipComments(owner, marker, false);
          break;
        }

        if (!nodeName && (marker == '|' || marker == '>')) {
          this.readMultilineStringFromMarker(owner, marker, result);
          break;
        }

        if (this.spacesBuffer != null && this.spacesBuffer.length() != 0) {
          this.spacesBuffer.setLength(0);
        }

        // See YamlReader#skipComments(char, boolean) for details about Character.isWhitespace(char) and YamlReader#skipComments(char, true/*!!*/).
        while (nodeName
            ? (marker != ':')
            : (!this.isEndMarker(marker) && (marker != ',' || this.bracketOpened) && (!Character.isWhitespace(marker) || !this.skipComments(owner, this.readRaw(), true)))) {
          if (nodeName && this.isEndMarker(marker)) {
            throw new IllegalStateException("Got a new line in node name: " + result);
          }

          if (Character.isWhitespace(marker)) {
            if (this.spacesBuffer == null) {
              this.spacesBuffer = new StringBuilder(4);
            }

            this.spacesBuffer.append(marker);
          } else {
            if (this.spacesBuffer != null && this.spacesBuffer.length() != 0) {
              result.append(this.spacesBuffer);
              this.spacesBuffer.setLength(0);
            }

            result.append(marker);
          }

          marker = this.readRaw();
        }

        if (!nodeName) {
          String string = result.toString();
          if (string.equals("null")) {
            return null;
          }

          if (this.yamlSerializable != null && owner != null) {
            this.yamlSerializable.saveStringStyle(owner, YamlWriter.StringStyle.NOT_QUOTED);
          }

          return string;
        }
      }
    }

    return result.toString();
  }

  @SuppressWarnings("DuplicatedCode")
  private void readMultilineStringFromMarker(@Nullable Field owner, char marker, StringBuilder result) {
    boolean keepNewLines = switch (marker) {
      case '>' -> {
        if (this.yamlSerializable != null && owner != null) {
          this.yamlSerializable.saveStringStyle(owner, YamlWriter.StringStyle.MULTILINE_FOLDED_AUTO_CLIPPED);
        }

        yield false;
      }
      case '|' -> {
        if (this.yamlSerializable != null && owner != null) {
          this.yamlSerializable.saveStringStyle(owner, YamlWriter.StringStyle.MULTILINE_LITERAL_AUTO_CLIPPED);
        }

        yield true;
      }
      default -> throw new IllegalStateException("Invalid multiline marker: " + marker);
    };

    char chomping = AbstractReader.NEW_LINE;
    marker = this.readRawIgnoreEmpty();
    if (marker == '+' || marker == '-') {
      chomping = marker;
      marker = this.readRawIgnoreEmpty();
    }

    int fixedIndent = 0;
    if (marker >= '1' && marker <= '9') {
      fixedIndent = marker - '0';
      marker = this.readRawIgnoreEmpty();
    }

    if (marker != AbstractReader.NEW_LINE) {
      throw new IllegalStateException("Got illegal marker while reading multiline string: " + marker);
    }

    marker = this.readRawIgnoreEmpty();
    int indentOffset = this.currentIndent - this.nodeIndent;
    if (indentOffset == 0) {
      throw new IllegalStateException("String should be indented");
    }

    if (fixedIndent == 0) {
      fixedIndent = indentOffset;
    }

    if (fixedIndent > indentOffset) {
      throw new IllegalStateException("Indentation marker does not match current indent offset: " + indentOffset);
    }

    int newLineCount = 0;
    boolean firstLine = true;
    while (indentOffset >= fixedIndent) {
      if (marker == AbstractReader.NEW_LINE) {
        ++newLineCount;
        marker = this.readRawIgnoreEmpty();
        indentOffset = (marker == AbstractReader.NEW_LINE ? this.newLineIndent : this.currentIndent) - this.nodeIndent;
      } else {
        if (!keepNewLines && newLineCount > 0) {
          if (newLineCount == 1) {
            result.append(' ');
          }
        }

        for (int i = newLineCount - (keepNewLines ? 0 : 1) - 1; i >= 0; --i) {
          result.append(AbstractReader.NEW_LINE);
        }

        if (newLineCount != 0 || firstLine) {
          for (int i = indentOffset - fixedIndent - 1; i >= 0; --i) {
            result.append(' ');
          }

          firstLine = false;
        }

        newLineCount = 0;
        result.append(marker);
        marker = this.readRaw();
      }
    }

    switch (chomping) {
      case '-' -> newLineCount = 0;
      case '+' -> {

      }
      default -> newLineCount = 1;
    }

    for (int i = 0; i < newLineCount; ++i) {
      result.append(AbstractReader.NEW_LINE);
    }

    this.setReuseBuffer();
  }

  @Override
  public void skipGuessingType(@Nullable Field owner) {
    synchronized (this) {
      this.skipGuessingTypeFromMarker(owner, this.readRawIgnoreEmpty());
    }
  }

  private void skipGuessingTypeFromMarker(@Nullable Field owner, char marker) {
    switch (marker) {
      case AbstractReader.NEW_LINE -> {
        char nextMarker = this.readRawIgnoreEmpty();
        this.setReuseBuffer();
        if (nextMarker == '-') {
          this.skipCollectionFromMarker(owner, marker);
        } else {
          this.skipMapFromMarker(owner, marker);
        }
      }
      case '-' -> {
        this.setReuseBuffer();
        this.skipCollectionFromMarker(owner, NEW_LINE);
      }
      case '[' -> this.skipCollectionFromMarker(owner, marker);
      case '{' -> this.skipMapFromMarker(owner, marker);
      case '"', '\'', '>', '|' -> this.skipStringFromMarker(owner, marker, false);
      default -> {
        if (this.isNullSkippedFromMarker(marker)) {
          return;
        }

        this.setSeekFromMarker(marker);
        String string = this.readStringFromMarker(owner, marker, false);
        if (string != null && (string.endsWith(":") || string.endsWith(": ") || string.contains(": "))) {
          this.unsetSeek();
          this.unsetTempRestoreNewLine();
          this.skipMapFromMarker(owner, NEW_LINE);
        } else {
          this.clearSeek();
        }
      }
    }
  }

  @Override
  public void skipCollection(@Nullable Field owner) {
    synchronized (this) {
      this.skipCollectionFromMarker(owner, this.readRawIgnoreEmpty());
    }
  }

  @SuppressFBWarnings("SA_FIELD_SELF_COMPARISON")
  private void skipCollectionFromMarker(@Nullable Field owner, char marker) {
    if (this.skipComments(owner, marker, false)) {
      marker = AbstractReader.NEW_LINE;
    }

    switch (marker) {
      case '[': {
        char nextMarker = this.readRawIgnoreEmptyAndNewLines();
        while (nextMarker != ']') {
          this.skipGuessingType(owner);
          nextMarker = this.readRawIgnoreEmptyAndNewLines();
        }

        break;
      }
      case AbstractReader.NEW_LINE: {
        this.skipComments(owner, this.readRawIgnoreEmpty(), true);
        char nextMarker = this.readRawIgnoreEmpty();
        if (nextMarker != '-') {
          throw new IllegalStateException("Got unknown marker when reading list: " + nextMarker);
        }
      } // Got '-' after newline, fall through here.
      case '-': {
        this.nodeIndent = this.currentIndent;
        char nextMarker = '-';
        int correctIndent = this.currentIndent;
        while (nextMarker == '-' && correctIndent == this.currentIndent) {
          this.setTempRestoreNewLine();
          this.skipGuessingType(owner);
          this.unsetTempRestoreNewLine();
          nextMarker = this.readRawIgnoreEmptyAndNewLines();
          if (this.skipComments(owner, nextMarker, false)) {
            nextMarker = this.readRawIgnoreEmptyAndNewLines();
          }
        }

        this.setReuseBuffer();
        break;
      }
      default: {
        if (!this.isNullSkippedFromMarker(marker)) {
          throw new IllegalStateException("Got unknown marker when reading list: " + marker);
        }
      }
    }
  }

  @Override
  public void skipMap(@Nullable Field owner) {
    boolean startOfFile = this.startOfFile;
    synchronized (this) {
      char marker = this.readRawIgnoreEmpty();
      if (startOfFile) {
        this.setTempRestoreNewLine();
      }

      this.skipMapFromMarker(owner, marker);
    }
  }

  @SuppressWarnings("DuplicatedCode")
  @SuppressFBWarnings("SA_FIELD_SELF_COMPARISON")
  private void skipMapFromMarker(@Nullable Field owner, char marker) {
    if (this.skipComments(owner, marker, false)) {
      marker = AbstractReader.NEW_LINE;
    }

    char nextMarker;
    if (this.tempRestoreNewLine) {
      nextMarker = marker;
      marker = AbstractReader.NEW_LINE;
      this.unsetTempRestoreNewLine();
    } else {
      nextMarker = this.readRawIgnoreEmptyAndNewLines();
    }

    boolean previousBracketOpened = this.bracketOpened;
    switch (marker) {
      case '{' -> {
        this.bracketOpened = true;
        while (nextMarker != '}') {
          this.skipStringFromMarker(owner, nextMarker, true);
          this.skipGuessingType(owner);
          nextMarker = this.readRawIgnoreEmptyAndNewLines();
        }

        this.bracketOpened = previousBracketOpened;
      }
      case AbstractReader.NEW_LINE -> {
        this.bracketOpened = false;
        int correctIndent = this.currentIndent;
        while (nextMarker != '\0' && correctIndent == this.currentIndent) {
          this.skipStringFromMarker(owner, nextMarker, true);
          this.skipGuessingType(owner);
          nextMarker = this.readRawIgnoreEmptyAndNewLines();
          if (this.skipComments(owner, nextMarker, false)) {
            nextMarker = this.readRawIgnoreEmptyAndNewLines();
          }
        }

        this.setReuseBuffer();
        this.bracketOpened = previousBracketOpened;
      }
      default -> {
        if (!this.isNullSkippedFromMarker(marker)) {
          throw new IllegalStateException("Got unknown marker when reading map: " + marker);
        }
      }
    }
  }

  @Override
  public void skipString(@Nullable Field owner) {
    synchronized (this) {
      this.skipStringFromMarker(owner, this.readRawIgnoreEmpty(), false);
    }
  }

  private void skipStringFromMarker(@Nullable Field owner, char marker, boolean nodeName) {
    switch (marker) {
      case '"' -> {
        while ((marker = this.readRaw()) != '"') {
          if (marker == '\\') {
            this.readRaw(); // To ensure the reading doesn't stop at \"
          } else if (nodeName && this.isEndMarker(marker)) {
            throw new IllegalStateException("Got a new line in node name.");
          }
        }

        if (nodeName) {
          marker = this.readRawIgnoreEmpty();
          if (marker != ':') {
            throw new IllegalStateException("Got illegal marker when reading node name: " + marker);
          }
        }
      }
      case '\'' -> {
        do {
          marker = this.readRaw();
          if (nodeName && this.isEndMarker(marker)) {
            throw new IllegalStateException("Got a new line in node name.");
          }
        } while (marker != '\'' || this.readRaw() == '\''); // 'text1 ''text2'' text3' reads as "text 'text2' text3".
        if (nodeName) {
          marker = this.readRawIgnoreEmpty();
          if (marker != ':') {
            throw new IllegalStateException("Got illegal marker when reading node name: " + marker);
          }
        }
      }
      default -> {
        if (marker == '#') {
          this.skipComments(owner, marker, false);
          break;
        }

        if (!nodeName && (marker == '|' || marker == '>')) {
          this.skipMultilineStringFromMarker(marker);
          break;
        }

        while (nodeName
            ? (marker != ':')
            // Here we don't need to care about bad comments, so we can ignore whitespace check, see YamlReader#skipComments(char, boolean) for details.
            : (!this.isEndMarker(marker) && (marker != ',' || this.bracketOpened) && !this.skipComments(owner, marker, false))) {
          if (nodeName && this.isEndMarker(marker)) {
            throw new IllegalStateException("Got a new line in node name.");
          }

          marker = this.readRaw();
        }
      }
    }
  }

  @SuppressWarnings("DuplicatedCode")
  private void skipMultilineStringFromMarker(char marker) {
    if (marker != '>' && marker != '|') {
      throw new IllegalStateException("Invalid multiline marker: " + marker);
    }

    marker = this.readRawIgnoreEmpty();
    if (marker == '+' || marker == '-') {
      marker = this.readRawIgnoreEmpty();
    }

    int fixedIndent = 0;
    if (marker >= '1' && marker <= '9') {
      fixedIndent = marker - '0';
      marker = this.readRawIgnoreEmpty();
    }

    if (marker != AbstractReader.NEW_LINE) {
      throw new IllegalStateException("Got illegal marker while skipping multiline string: " + marker);
    }

    marker = this.readRawIgnoreEmpty();
    int indentOffset = this.currentIndent - this.nodeIndent;
    if (indentOffset == 0) {
      throw new IllegalStateException("String should be indented");
    }

    if (fixedIndent == 0) {
      fixedIndent = indentOffset;
    }

    if (fixedIndent > indentOffset) {
      throw new IllegalStateException("Indentation marker does not match current indent offset: " + indentOffset);
    }

    while (fixedIndent <= indentOffset) {
      if (marker == AbstractReader.NEW_LINE) {
        marker = this.readRawIgnoreEmpty();
        indentOffset = (marker == AbstractReader.NEW_LINE ? this.newLineIndent : this.currentIndent) - this.nodeIndent;
      } else {
        marker = this.readRaw();
      }
    }

    this.setReuseBuffer();
  }

  @Override
  public boolean skipComments(@Nullable Field owner, char marker, boolean reuse) {
    synchronized (this) {
      if (marker == '#') {
        while (true) {
          if (this.isEndMarker(this.readRaw())) {
            break;
          }
        }

        this.readRawIgnoreEmptyAndNewLines();
        this.setReuseBuffer();
        return true;
      }

      // Need in case if we're parsing plain string without quotes.
      // (e.g. `string: string#bad comment` would be parsed as "text#bad comment", otherwise `string: text #normal comment` would be parsed as "text",
      // see default case in YamlReader#readStringFromMarker(char, boolean))
      if (reuse) {
        this.setReuseBuffer();
      }

      return false;
    }
  }

  protected boolean isEndMarker(char marker) {
    return marker == '\0' || marker == AbstractReader.NEW_LINE;
  }

  @Override
  public void setSeek() {
    synchronized (this) {
      this.seekIndent = this.currentIndent;
    }

    super.setSeek();
  }

  @Override
  public void setSeekFromMarker(char marker) {
    synchronized (this) {
      this.seekIndent = this.currentIndent - 1;
    }

    super.setSeekFromMarker(marker);
  }

  @Override
  public void unsetSeek() {
    synchronized (this) {
      this.currentIndent = this.seekIndent;
    }

    super.unsetSeek();
  }

  @Override
  public char readRaw() {
    this.startOfFile = false;
    synchronized (this) {
      boolean shouldIndent = !this.isReuseBuffer();
      char character = super.readRaw();
      if (character == AbstractReader.NEW_LINE) {
        this.newLineIndent = this.currentIndent + 1;
        this.currentIndent = 0;
      } else if (shouldIndent) {
        ++this.currentIndent;
      }

      return character;
    }
  }

  private boolean isNullSkippedFromMarker(char marker) {
    this.setSeek();
    if (marker == 'n' && this.readRaw() == 'u' && this.readRaw() == 'l' && this.readRaw() == 'l') {
      char endMarker = this.readRawIgnoreEmpty();
      if (this.isEndMarker(endMarker) || (endMarker == ',' && this.bracketOpened)) {
        this.clearSeek();
        return true;
      }
    }

    this.unsetSeek();
    return false;
  }

  private boolean skipChar(char expected) {
    if (expected == this.readRawIgnoreEmptyAndNewLines()) {
      return true;
    } else {
      this.setReuseBuffer();
      return false;
    }
  }

  private void setTempRestoreNewLine() {
    this.tempRestoreNewLine = true;
  }

  private void unsetTempRestoreNewLine() {
    this.tempRestoreNewLine = false;
  }
}
