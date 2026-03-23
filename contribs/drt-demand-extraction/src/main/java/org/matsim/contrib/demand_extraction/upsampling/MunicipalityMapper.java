package org.matsim.contrib.demand_extraction.upsampling;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.feature.NameImpl;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.utils.gis.GeoFileReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class MunicipalityMapper {

    private static final Logger log = LogManager.getLogger(MunicipalityMapper.class);

    private final STRtree spatialIndex = new STRtree();
    private final String attributeName;
    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final List<SimpleFeature> features = new ArrayList<>();

    public MunicipalityMapper(String shapefilePath, String layerName, String attributeName) {
        this.attributeName = attributeName;

        String resolvedPath = shapefilePath;
        // If it's a zip, extract the .gpkg file to a temp location
        if (shapefilePath.endsWith(".zip")) {
            resolvedPath = extractGpkgFromZip(shapefilePath);
        }

        Collection<SimpleFeature> featureCollection;
        if (layerName != null && resolvedPath.endsWith(".gpkg")) {
            featureCollection = GeoFileReader.getAllFeatures(resolvedPath, new NameImpl(layerName));
        } else {
            featureCollection = GeoFileReader.getAllFeatures(resolvedPath);
        }

        for (SimpleFeature feature : featureCollection) {
            Geometry geom = (Geometry) feature.getDefaultGeometry();
            if (geom != null) {
                spatialIndex.insert(geom.getEnvelopeInternal(), feature);
                features.add(feature);
            }
        }
        spatialIndex.build();
        log.info("Loaded {} municipality polygons from {}", features.size(), shapefilePath);
    }

    /**
     * Extract the first .gpkg file from a zip archive to a temp directory.
     */
    private static String extractGpkgFromZip(String zipPath) {
        try (ZipFile zip = new ZipFile(zipPath)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".gpkg") && !entry.isDirectory()) {
                    Path tempDir = Files.createTempDirectory("vg250_");
                    Path gpkgPath = tempDir.resolve(Path.of(entry.getName()).getFileName());
                    try (InputStream is = zip.getInputStream(entry)) {
                        Files.copy(is, gpkgPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                    log.info("Extracted {} from zip to {}", entry.getName(), gpkgPath);
                    gpkgPath.toFile().deleteOnExit();
                    tempDir.toFile().deleteOnExit();
                    return gpkgPath.toString();
                }
            }
            throw new IllegalArgumentException("No .gpkg file found inside zip: " + zipPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to extract .gpkg from zip: " + zipPath, e);
        }
    }

    public String getMunicipality(Coord coord) {
        Point point = geometryFactory.createPoint(
                new org.locationtech.jts.geom.Coordinate(coord.getX(), coord.getY()));

        @SuppressWarnings("unchecked")
        List<SimpleFeature> candidates = spatialIndex.query(point.getEnvelopeInternal());

        for (SimpleFeature feature : candidates) {
            Geometry geom = (Geometry) feature.getDefaultGeometry();
            if (geom.contains(point)) {
                Object attr = feature.getAttribute(attributeName);
                return attr != null ? attr.toString() : null;
            }
        }
        return null;
    }

    public Map<Id<Person>, String> mapPopulation(Population population) {
        Map<Id<Person>, String> mapping = new LinkedHashMap<>();
        int unmapped = 0;

        for (Person person : population.getPersons().values()) {
            Coord homeCoord = findHomeCoord(person);
            if (homeCoord == null) {
                unmapped++;
                continue;
            }

            String municipality = getMunicipality(homeCoord);
            if (municipality == null) {
                unmapped++;
                continue;
            }

            mapping.put(person.getId(), municipality);
        }

        if (unmapped > 0) {
            log.warn("{} persons could not be mapped to a municipality (no home activity or outside shapefile extent)",
                    unmapped);
        }
        log.info("Mapped {} of {} persons to municipalities",
                mapping.size(), population.getPersons().size());
        return mapping;
    }

    static Coord findHomeCoord(Person person) {
        Plan plan = person.getSelectedPlan();
        if (plan == null && !person.getPlans().isEmpty()) {
            plan = person.getPlans().get(0);
        }
        if (plan == null) return null;

        for (PlanElement element : plan.getPlanElements()) {
            if (element instanceof Activity activity) {
                if (activity.getType().toLowerCase().startsWith("home")) {
                    return activity.getCoord();
                }
            }
        }
        return null;
    }
}
