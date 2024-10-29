package dev.keesmand.trakteeractions.util;

import com.google.gson.*;
import dev.keesmand.trakteeractions.model.Donation;

import java.lang.reflect.Type;

public class DonationDeserializer implements JsonDeserializer<Donation> {

    @Override
    public Donation deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        JsonObject jsonObject = json.getAsJsonObject();

        String supporter_name = jsonObject.get("supporter_name").getAsString();
        String support_message = !jsonObject.get("support_message").isJsonNull()
                ? jsonObject.get("support_message").getAsString()
                : "";
        int quantity = jsonObject.get("quantity").getAsInt();
        int amount = jsonObject.get("amount").getAsInt();
        String unit_name = jsonObject.get("unit_name").getAsString();
        String updated_at = jsonObject.get("updated_at").getAsString();

        return new Donation(supporter_name, support_message, quantity, amount, unit_name, updated_at);
    }
}
