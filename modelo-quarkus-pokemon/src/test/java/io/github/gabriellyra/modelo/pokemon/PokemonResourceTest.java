package io.github.gabriellyra.modelo.pokemon;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import io.github.gabriellyra.modelo.pokemon.domain.Pokemon;
import io.github.gabriellyra.modelo.pokemon.repository.PokemonRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

@QuarkusTest
class PokemonResourceTest {

    @Inject
    PokemonRepository repository;

    @Test
    void deveCarregarPokemonsIniciaisNoStartup() {
        given()
                .when().get("/pokemons")
                .then()
                .statusCode(200)
                .body("size()", greaterThanOrEqualTo(7))
                .body("nome", hasItems("Bulbasaur", "Charmander", "Squirtle", "Pikachu"));
    }

    @Test
    void deveFiltrarPorTipoConsiderandoTipoSecundario() {
        given()
                .queryParam("tipo", "VENENOSO")
                .when().get("/pokemons")
                .then()
                .statusCode(200)
                .body("nome", hasItems("Bulbasaur", "Gengar"))
                .body("nome", not(hasItem("Pikachu")));
    }

    @Test
    void deveFiltrarPorParteDoNome() {
        given()
                .queryParam("nome", "char")
                .when().get("/pokemons")
                .then()
                .statusCode(200)
                .body("nome", everyItem(equalTo("Charmander")));
    }

    @Test
    void deveBuscarPorId() {
        given()
                .when().get("/pokemons/1")
                .then()
                .statusCode(200)
                .body("nome", equalTo("Bulbasaur"))
                .body("tipoPrimario", equalTo("GRAMA"))
                .body("tipoSecundario", equalTo("VENENOSO"));
    }

    @Test
    void deveCriarPokemon() {
        Integer id = given()
                .contentType(ContentType.JSON)
                .body(pokemonJson(152, "Chikorita", "GRAMA"))
                .when().post("/pokemons")
                .then()
                .statusCode(201)
                .header("Location", notNullValue())
                .body("id", notNullValue())
                .body("nome", equalTo("Chikorita"))
                .body("criadoEm", notNullValue())
                .extract().path("id");

        given().when().get("/pokemons/" + id)
                .then().statusCode(200).body("numeroPokedex", equalTo(152));
    }

    @Test
    void deveRejeitarNumeroDaPokedexDuplicado() {
        given()
                .contentType(ContentType.JSON)
                .body(pokemonJson(25, "Outro Pikachu", "ELETRICO"))
                .when().post("/pokemons")
                .then()
                .statusCode(409)
                .body("status", equalTo(409));
    }

    @Test
    void deveRejeitarPayloadInvalido() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"numeroPokedex\": -1, \"nome\": \"\"}")
                .when().post("/pokemons")
                .then()
                .statusCode(400);
    }

    @Test
    void deveAtualizarPokemon() {
        Integer id = criar(155, "Cyndaquil", "FOGO");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"numeroPokedex": 155, "nome": "Cyndaquil Shiny", "tipoPrimario": "FOGO",
                         "alturaMetros": 0.5, "pesoKg": 7.9}
                        """)
                .when().put("/pokemons/" + id)
                .then()
                .statusCode(200)
                .body("nome", equalTo("Cyndaquil Shiny"))
                .body("pesoKg", equalTo(7.9f))
                .body("atualizadoEm", notNullValue());
    }

    @Test
    void deveRetornar404AoAtualizarPokemonInexistente() {
        given()
                .contentType(ContentType.JSON)
                .body(pokemonJson(999, "Fantasma", "FANTASMA"))
                .when().put("/pokemons/99999")
                .then()
                .statusCode(404);
    }

    @Test
    void deveExcluirLogicamente() {
        Integer id = criar(158, "Totodile", "AGUA");

        given().when().delete("/pokemons/" + id).then().statusCode(204);

        given().when().get("/pokemons/" + id).then().statusCode(404);
        given().when().get("/pokemons").then().body("nome", not(hasItem("Totodile")));
        given().when().delete("/pokemons/" + id).then().statusCode(404);

        Pokemon noBanco = repository.findById(id.longValue());
        assertNotNull(noBanco, "registro deve continuar na tabela");
        assertFalse(noBanco.isAtivo());
        assertNotNull(noBanco.getExcluidoEm());
    }

    @Test
    void devePermitirReutilizarNumeroDePokemonExcluido() {
        Integer id = criar(161, "Sentret", "NORMAL");
        given().when().delete("/pokemons/" + id).then().statusCode(204);

        given()
                .contentType(ContentType.JSON)
                .body(pokemonJson(161, "Sentret", "NORMAL"))
                .when().post("/pokemons")
                .then()
                .statusCode(201);
    }

    private Integer criar(int numero, String nome, String tipo) {
        return given()
                .contentType(ContentType.JSON)
                .body(pokemonJson(numero, nome, tipo))
                .when().post("/pokemons")
                .then().statusCode(201)
                .extract().path("id");
    }

    private String pokemonJson(int numero, String nome, String tipo) {
        return """
                {"numeroPokedex": %d, "nome": "%s", "tipoPrimario": "%s", "alturaMetros": 0.9, "pesoKg": 6.4}
                """.formatted(numero, nome, tipo);
    }
}
