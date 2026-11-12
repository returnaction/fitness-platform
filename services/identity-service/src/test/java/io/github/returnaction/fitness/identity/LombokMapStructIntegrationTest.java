package io.github.returnaction.fitness.identity;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.Getter;
import lombok.Setter;
import org.junit.jupiter.api.Test;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

class LombokMapStructIntegrationTest {

    @Test
    void mapsPropertiesGeneratedByLombok() {
        Source source = new Source();
        source.setName("identity");

        assertThat(Mappers.getMapper(ExampleMapper.class).map(source).getName()).isEqualTo("identity");
    }

    @Getter
    @Setter
    public static class Source {

        private String name;
    }

    @Getter
    @Setter
    public static class Target {

        private String name;
    }

    @Mapper
    public interface ExampleMapper {

        Target map(Source source);
    }
}
