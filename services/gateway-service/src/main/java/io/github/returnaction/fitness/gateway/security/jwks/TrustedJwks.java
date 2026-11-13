package io.github.returnaction.fitness.gateway.security.jwks;

import java.util.Set;

import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.KeyOperation;

/**
 * Fully validates a candidate before any key can become visible to requests.
 */
public final class TrustedJwks {

    private TrustedJwks() {
    }

    public static List<ECKey> parse(String json) throws Exception {
        var parsed = JWKSet.parse(json);
        var ids = new HashSet<String>();
        var usable = new ArrayList<ECKey>();
        for (var key : parsed.getKeys()) {
            String id = key.getKeyID();
            if (id == null || id.isBlank() || !ids.add(id) || key.isPrivate()) {
                throw new IllegalArgumentException("Invalid JWKS key identifiers or private material");
            }
            if (key instanceof ECKey ec && Curve.P_256.equals(ec.getCurve())
                    && (ec.getAlgorithm() == null || JWSAlgorithm.ES256.equals(ec.getAlgorithm()))
                    && (ec.getKeyUse() == null || KeyUse.SIGNATURE.equals(ec.getKeyUse()))
                    && (ec.getKeyOperations() == null || ec.getKeyOperations().equals(Set.of(KeyOperation.VERIFY)))) {
                ec.toECPublicKey();
                usable.add(ec);
            }
        }
        if (usable.isEmpty()) {
            throw new IllegalArgumentException("No usable ES256 public keys");
        }
        return List.copyOf(usable);
    }
}
