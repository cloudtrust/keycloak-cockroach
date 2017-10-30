package io.cloudtrust.keycloak.liquibaseAdapter;

import io.cloudtrust.keycloak.liquibaseAdapter.pojo.liquibase.*;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The purpose of this class is to transform the keycloak liquibase schema creation scripts into versions that
 * are compatible with cockroachdb.
 *
 * @author Alistair Doswald
 */
public class ChangeLogEditor {

    private DatabaseChangeLog dcl;
    private List<DatabaseChangeLog.ChangeSet> changeSetList;
    private String fileName;
    private final Marshaller marshaller;

    /**
     * Standard constructor
     *
     */
    public ChangeLogEditor() {
        Marshaller local = null;
        try {
            JAXBContext context = JAXBContext.newInstance(DatabaseChangeLog.class);
            local = context.createMarshaller();
            local.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            local.setProperty(Marshaller.JAXB_SCHEMA_LOCATION, "http://www.liquibase.org/xml/ns/dbchangelog " +
                    "http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-3.2.xsd");
        } catch (JAXBException e) {
            //do nothing, but a nullpointer will be called later in the unlikely event of an error
        }
        marshaller = local;
    }

    /**
     * Loads a database change log file and extracts the list of ChangeSets
     *
     * @param fileName the name of the XML file containing the database changelog
     * @throws JAXBException thrown if there's a problem unmashalling the file
     */
    public void loadDatabaseChangeLog(String fileName) throws JAXBException {
        File file = new File(fileName);
        this.fileName = fileName;
        JAXBContext context = JAXBContext.newInstance(DatabaseChangeLog.class);
        Unmarshaller unmarshaller = context.createUnmarshaller();
        dcl = (DatabaseChangeLog) unmarshaller.unmarshal(file);
        changeSetList = dcl.getChangeSetOrIncludeOrIncludeAll().stream()
                .filter(DatabaseChangeLog.ChangeSet.class::isInstance).map(DatabaseChangeLog.ChangeSet.class::cast)
                .collect(Collectors.toList());
    }

    /**
     * Creates a "table name - Create Table" map
     *
     * @param changeSet the changeset from which to extract the map
     * @return the map of tableNames to CreateTable objects
     */
    public Map<String, CreateTable> getCreateTableMap(DatabaseChangeLog.ChangeSet changeSet) {
        return changeSet.getChangeSetChildren().stream().filter(CreateTable.class::isInstance)
                .map(CreateTable.class::cast)
                .collect(Collectors.toMap(p -> p.getTableName(), p -> p));
    }

    /**
     * Creates a "column name - column" map
     *
     * @param table the Create Table object from which to get the columns
     * @return the map of columnName to Column objects
     */
    public Map<String, Column> getColumnMap(CreateTable table) {
        return table.getColumn().stream().collect(Collectors.toMap(p -> p.getName(), p -> p));
    }

    /**
     * For a given Column, returns the first contraint, or creates one and attaches it to the Column
     *
     * @param c the Column object
     * @return the (possibly newly created) constraint
     */
    public Constraints getColumnConstraints(Column c) {
        List<Constraints> constraints = c.getContent().stream().filter(Constraints.class::isInstance)
                .map(Constraints.class::cast)
                .collect(Collectors.toList());
        if (constraints.isEmpty()) {
            Constraints constraint = new Constraints();
            c.getContent().add(constraint);
            return constraint;
        } else {
            return constraints.get(0);
        }
    }

    /**
     * Primary keys cannot be assigned after time in cockroachdb. This method merges all "add primary key" commands into
     * the "create table" commands.
     * If this cannot be done because the table was created in a previous script, the method prints the constraints for
     * which this is a problem.
     */
    public void mergeAddPrimeryKeyIntoCreateTable() {
        for (DatabaseChangeLog.ChangeSet changeSet : changeSetList) {
            Map<String, CreateTable> tables = getCreateTableMap(changeSet);
            List<AddPrimaryKey> primaryKeys = changeSet.getChangeSetChildren().stream()
                    .filter(AddPrimaryKey.class::isInstance).map(AddPrimaryKey.class::cast).collect(Collectors.toList());
            for (AddPrimaryKey pk : primaryKeys) {
                CreateTable table = tables.get(pk.getTableName());
                if (table == null) {
                    System.err.println("ChangeSet " + changeSet.getId() + ": Unable to add primary key " +
                            pk.getConstraintName() + " to table " + pk.getTableName() + " -> Skipping");
                    continue;
                }
                Map<String, Column> columnMap = getColumnMap(table);
                String[] columnNames = pk.getColumnNames().split(", *");
                for (String columnName : columnNames) {
                    Column column = columnMap.get(columnName);
                    Constraints constraint = getColumnConstraints(column);
                    constraint.setPrimaryKey("true");
                    constraint.setPrimaryKeyName(pk.getConstraintName());
                }
                changeSet.getChangeSetChildren().remove(pk);
            }
        }
    }

    /**
     * Cockroachdb must have indexes on all columns used in creating primary keys. This method creates indexes on all
     * columns for which a foreign key is going to be added.
     * <p>
     * In addition, it moves all new index creations and all foreign key creations into two new changesets:
     * - For index creation to avoid conflicts with the rest of the changeset
     * - For foreign keys because otherwise cockroach doesn't register the create index modifications as having been
     * done when the "add foreign key" is called
     * <p>
     * FIXME this method creates an index whether one is necessary or not. This MUST be changed to check if a suitable index already exists
     */
    public void createIndexesForForeignKeys() {
        for (DatabaseChangeLog.ChangeSet changeset : changeSetList) {
            List<AddForeignKeyConstraint> foreignKeyConstraints = changeset.getChangeSetChildren().stream()
                    .filter(AddForeignKeyConstraint.class::isInstance).map(AddForeignKeyConstraint.class::cast)
                    .collect(Collectors.toList());
            if (foreignKeyConstraints.isEmpty())
                return;
            DatabaseChangeLog.ChangeSet foreignChangeSet = new DatabaseChangeLog.ChangeSet();
            foreignChangeSet.setAuthor(changeset.getAuthor());
            foreignChangeSet.setId(changeset.getId() + "_foreign");
            DatabaseChangeLog.ChangeSet indexChangeSet = new DatabaseChangeLog.ChangeSet();
            indexChangeSet.setAuthor(changeset.getAuthor());
            indexChangeSet.setId(changeset.getId() + "_index");
            dcl.getChangeSetOrIncludeOrIncludeAll().add(indexChangeSet);
            dcl.getChangeSetOrIncludeOrIncludeAll().add(foreignChangeSet);
            for (AddForeignKeyConstraint foreignKeyConstraint : foreignKeyConstraints) {
                CreateIndex createIndex = new CreateIndex();
                createIndex.setIndexName(foreignKeyConstraint.getConstraintName());
                createIndex.setTableName(foreignKeyConstraint.getBaseTableName());
                for (String colmnName : foreignKeyConstraint.getBaseColumnNames().split(", *")) {
                    Column column = new Column();
                    column.setName(colmnName);
                    createIndex.getColumn().add(column);
                }
                indexChangeSet.getChangeSetChildren().add(createIndex);
                changeset.getChangeSetChildren().remove(foreignKeyConstraint);
                foreignChangeSet.getChangeSetChildren().add(foreignKeyConstraint);
            }
        }
    }

    /**
     * In cockroach DB creating a unique constraint is possible, but in reality it just creates a unique index, and
     * notes that a unique constraint exists (creating a unique index does EXACTLY the same thing)
     * Dropping a unique index is possible, but not dropping a unique constraint. This method translates all "drop unique
     * constraints" into "drop index" commands.
     * <p>
     * TODO see if we want to contribute to cockroach DB so that a "drop unique constraint" actually does a "drop index", because this is ridiculous
     */
    public void changeDropUniqueConstraintToDropIndex() {
        for (DatabaseChangeLog.ChangeSet changeset : changeSetList) {
            List<DropUniqueConstraint> dropUniqueConstraints = changeset.getChangeSetChildren().stream().
                    filter(DropUniqueConstraint.class::isInstance).map(DropUniqueConstraint.class::cast)
                    .collect(Collectors.toList());
            for (DropUniqueConstraint dropUniqueConstraint : dropUniqueConstraints) {
                DropIndex dropIndex = new DropIndex();
                dropIndex.setTableName(dropUniqueConstraint.getTableName());
                dropIndex.setIndexName(dropUniqueConstraint.getConstraintName());
                changeset.getChangeSetChildren().add(changeset.getChangeSetChildren()
                        .indexOf(dropUniqueConstraint), dropIndex);
                changeset.getChangeSetChildren().remove(dropUniqueConstraint);
            }
        }
    }

    /**
     * Prints the current DatabaseChangeLog to the same path as the initially read file, but attaching the
     * -cockroachdb suffix. If the file already exists, it will be replaced.
     *
     * @throws JAXBException thrown if there's an error marshalling to the file
     * @throws IOException   thrown if there's a problem writing the file
     */
    public void printToFile() throws JAXBException, IOException {
        File input = new File(fileName);
        String outputFileName = fileName.substring(0, fileName.lastIndexOf('.')) + "-cockroachdb.xml";
        File output = new File(outputFileName);
        Files.deleteIfExists(output.toPath());
        Files.createFile(output.toPath());
        marshaller.marshal(dcl, output);
    }

    /**
     * Prints the current DatabaseChangeLog to a String
     *
     * @return
     */
    public String toString() {
        try {
            StringWriter sw = new StringWriter();
            marshaller.marshal(dcl, sw);
            return sw.toString();
        } catch (JAXBException e) {
            return "No valid content: " + e.getMessage();
        }
    }

    public DatabaseChangeLog getDcl() {
        return dcl;
    }

    public void setDcl(DatabaseChangeLog dcl) {
        this.dcl = dcl;
    }

    public static void main(String[] in) {
        Path changeLogsLocation = Paths.get("/home/add/CloudTrust/keycloak-stable/model/jpa/src/main/resources/META-INF/");
        Path current = null;
        ChangeLogEditor cle = new ChangeLogEditor();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(changeLogsLocation, "jpa-changelog*.xml")) {
            List<Path> list = new ArrayList<>();
            stream.forEach(list::add);
            list.sort(Comparator.comparing(Path::toString));
            list.removeIf(path -> path.toString().contains("-db2"));
            list.removeIf(path -> path.toString().contains("-cockroachdb"));
            for (Path entry : list) {
                current = entry;
                cle.loadDatabaseChangeLog(entry.toString());
                cle.mergeAddPrimeryKeyIntoCreateTable();
                cle.createIndexesForForeignKeys();
                cle.changeDropUniqueConstraintToDropIndex();
                cle.printToFile();
            }
        } catch (Exception e) {
            System.err.println("Path: " + current);
            e.printStackTrace();
        }
    }
}
