package io.github.returnaction.fitness.challenge.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;

/**
 * Частичное изменение черновика. Явный null очищает поле; отсутствие поля его
 * не меняет, поэтому для каждого поля хранится признак присутствия.
 */
@Getter
public class UpdateDraftRequest {

    @NotNull
    @Positive
    private Long version;

    @Size(min = 1, max = 120)
    private String title;

    @Size(max = 2000)
    private String description;

    @Min(1)
    @Max(1_000_000)
    private Long stakeAmount;

    @Min(1)
    @Max(30)
    private Integer durationDays;

    private boolean titlePresent;
    private boolean descriptionPresent;
    private boolean stakeAmountPresent;
    private boolean durationDaysPresent;

    @JsonSetter("version")
    public void setVersion(Long version) {
        this.version = version;
    }

    @JsonSetter("title")
    public void setTitle(String title) {
        this.title = title;
        titlePresent = true;
    }

    @JsonSetter("description")
    public void setDescription(String description) {
        this.description = description;
        descriptionPresent = true;
    }

    @JsonSetter("stakeAmount")
    public void setStakeAmount(Long stakeAmount) {
        this.stakeAmount = stakeAmount;
        stakeAmountPresent = true;
    }

    @JsonSetter("durationDays")
    public void setDurationDays(Integer durationDays) {
        this.durationDays = durationDays;
        durationDaysPresent = true;
    }
}
