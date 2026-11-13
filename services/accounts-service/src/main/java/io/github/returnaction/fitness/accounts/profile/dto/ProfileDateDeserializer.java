package io.github.returnaction.fitness.accounts.profile.dto;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdScalarDeserializer;

/**
 * Accepts only a calendar date string; Jackson's default also accepts arrays
 * and epoch days.
 */
public final class ProfileDateDeserializer extends StdScalarDeserializer<LocalDate> {

    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

    public ProfileDateDeserializer() {
        super(LocalDate.class);
    }

    @Override
    public LocalDate deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            return (LocalDate) context.handleUnexpectedToken(LocalDate.class, parser);
        }
        String value = parser.getString();
        if (DATE.matcher(value).matches()) {
            try {
                return LocalDate.parse(value);
            } catch (DateTimeParseException invalid) {
                // Report a format error; the HTTP handler does not expose the supplied value.
            }
        }
        return (LocalDate) context.handleWeirdStringValue(LocalDate.class, value, "Expected YYYY-MM-DD.");
    }
}
