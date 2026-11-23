package com.example.notifications;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.List;
import java.util.Map;

@Path("/v1/admin/webhook-origins")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public final class AdminResource {
    public record OriginInput(@NotBlank String origin) {}
    private final Store store;
    private final WebhookPolicy policy;
    public AdminResource(Store store,WebhookPolicy policy) {this.store=store;this.policy=policy;}
    private void administrator(SecurityContext user) {
        if(user==null || !user.isUserInRole("administrator")) throw new ForbiddenException("Administrator credentials required");
    }
    @GET public List<String> list(@Context SecurityContext user,
            @QueryParam("limit") @DefaultValue("100") int limit,
            @QueryParam("offset") @DefaultValue("0") int offset) {
        administrator(user);
        if(limit<1 || limit>100 || offset<0 || offset>100000) throw new BadRequestException("Invalid pagination");
        return store.webhookOrigins(limit,offset);
    }
    @POST public Map<String,Object> approve(@Context SecurityContext user,@Valid OriginInput input) {
        administrator(user);
        if(input==null) throw new BadRequestException("Origin is required");
        var origin=policy.validateOrigin(input.origin());
        store.setWebhookOrigin(origin,true);
        return Map.of("origin",origin,"approved",true);
    }
    @DELETE public Response revoke(@Context SecurityContext user,@QueryParam("origin") String value) {
        administrator(user);
        if(value==null) throw new BadRequestException("Origin is required");
        // Revocation must work even if DNS is unavailable or has become private.
        var uri=java.net.URI.create(value);
        if(uri.getHost()==null) throw new BadRequestException("Invalid origin");
        store.setWebhookOrigin(WebhookPolicy.origin(uri),false);
        return Response.noContent().build();
    }
}
