package me.vlosses.vLosses;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextServiceTest {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test
    void externalMetadataCannotInjectMiniMessageEvents() {
        String hostile = "<click:run_command:'/op somebody'><red>Admin</red></click>";
        Component value = TextService.legacyValue(hostile);
        assertEquals(hostile, PLAIN.serialize(value));
        assertNull(value.clickEvent());
        assertTrue(value.children().stream().allMatch(child -> child.clickEvent() == null));
    }

    @Test
    void legacyMetadataRetainsRankColorsWithoutParsingTags() {
        Component value = TextService.legacyValue("&aVIP <bold>");
        assertEquals("VIP <bold>", PLAIN.serialize(value));
        assertEquals(NamedTextColor.GREEN, value.color());
    }

    @Test
    void normalAmpersandsRemainVisible() {
        Component value = MiniMessage.miniMessage().deserialize(TextService.legacyToMiniMessage("Research & Development & team"));
        assertEquals("Research & Development & team", PLAIN.serialize(value));
    }

    @Test
    void urlQueryParametersInsideTrustedTagsRemainIntact() {
        Component value = MiniMessage.miniMessage().deserialize(TextService.legacyToMiniMessage("<click:open_url:'https://example.com/?a=1&b=2'>Open</click>"));
        assertEquals("https://example.com/?a=1&b=2", value.clickEvent().value());
    }

    @Test
    void hexAndSectionColorsAreConverted() {
        Component hex = MiniMessage.miniMessage().deserialize(TextService.legacyToMiniMessage("&#12abefHello"));
        assertEquals("Hello", PLAIN.serialize(hex));
        assertEquals(TextColor.color(0x12abef), hex.color());
        Component repeated = MiniMessage.miniMessage().deserialize(TextService.legacyToMiniMessage("\u00a7x\u00a71\u00a72\u00a7a\u00a7b\u00a7e\u00a7fHello"));
        assertEquals(hex, repeated);
    }

    @Test
    void repeatedStaticTemplatesUseBoundedRenderCache() {
        TextService service = new TextService(null);
        for (int index = 0; index < 3_000; index++) service.render(null, "<gray>Line " + index);
        assertEquals(2_048, service.cacheSize());
        service.close();
        assertEquals(0, service.cacheSize());
    }
}
