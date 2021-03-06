/*
 * Copyright (c) 2016 Regents of the University of Minnesota.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.umn.biomedicus.acronym;

import com.google.inject.Inject;
import com.google.inject.ProvidedBy;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import edu.umn.biomedicus.annotations.Setting;
import edu.umn.biomedicus.application.DataLoader;
import edu.umn.biomedicus.common.types.text.Token;
import edu.umn.biomedicus.exc.BiomedicusException;
import edu.umn.biomedicus.serialization.YamlSerialization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import javax.annotation.Nullable;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * An implementation of an acronym model that uses word vectors and a cosine distance metric
 *
 * @author Greg Finley
 * @since 1.5.0
 */
@ProvidedBy(AcronymVectorModel.Loader.class)
class AcronymVectorModel implements AcronymModel {

    private static final Logger LOGGER = LoggerFactory.getLogger(AcronymVectorModel.class);

    /**
     * A vector space with a built dictionary to use at test time
     */
    private final WordVectorSpace wordVectorSpace;

    private final AcronymExpansionsModel acronymExpansionsModel;

    /**
     * Maps long forms to their trained word vectors
     */
    private final Map<String, SparseVector> senseMap;

    /**
     * The alignment model will guess an acronym's full form based on its alignment if we don't know what it is.
     */
    private final AlignmentModel alignmentModel;

    /**
     * Constructor. Needs several things already made:
     *
     * @param wordVectorSpace the vector space (most importantly dictionary) used to build context vectors
     * @param senseMap          which maps between senses and their context vectors
     * @param alignmentModel    a model used for alignment of unknown acronyms
     */
    AcronymVectorModel(WordVectorSpace wordVectorSpace, Map<String, SparseVector> senseMap, AcronymExpansionsModel acronymExpansionsModel, @Nullable AlignmentModel alignmentModel) {
        this.acronymExpansionsModel = acronymExpansionsModel;
        this.senseMap = senseMap;
        this.wordVectorSpace = wordVectorSpace;
        this.alignmentModel = alignmentModel;
    }

    /**
     * Will return a list of the possible senses for this acronym
     *
     * @param token a Token
     * @return a List of Strings of all possible senses
     */
    public Collection<String> getExpansions(Token token) {
        String acronym = Acronyms.standardAcronymForm(token);
        Collection<String> expansions = acronymExpansionsModel.getExpansions(acronym);
        if (expansions != null) {
            return expansions;
        }
        return Collections.emptyList();
    }

    /**
     * Does the model know about this acronym?
     * @param token a token
     * @return true if this token's text is a known acronym
     */
    public boolean hasAcronym(Token token) {
        String acronym = Acronyms.standardAcronymForm(token);
        return acronymExpansionsModel.hasExpansions(acronym);
    }

    /**
     * Will return the model's best guess for the sense of this acronym
     *
     * @param context a list of tokens including the full context for this acronym
     * @param forThisIndex an integer specifying the index of the acronym
     * @return
     */
    @Override
    public String findBestSense(List<Token> context, int forThisIndex) {

        String acronym = Acronyms.standardAcronymForm(context.get(forThisIndex));

        // If the model doesn't contain this acronym, make sure it doesn't contain an upper-case version of it
        Collection<String> senses = acronymExpansionsModel.getExpansions(acronym);
        if (senses == null) {
            senses = acronymExpansionsModel.getExpansions(acronym.toUpperCase());
        }
        if (senses == null) {
            senses = acronymExpansionsModel.getExpansions(acronym.replace(".", ""));
        }
        if (senses == null) {
            senses = acronymExpansionsModel.getExpansions(acronym.toLowerCase());
        }
        if (senses == null && alignmentModel != null) {
            senses = alignmentModel.findBestLongforms(acronym);
        }
        if (senses == null || senses.size() == 0) {
            return Acronyms.UNKNOWN;
        }

        // If the acronym is unambiguous, our work is done
        if (senses.size() == 1) {
            return senses.iterator().next();
        }

        List<String> usableSenses = new ArrayList<>();
        // Be sure that there even are disambiguation vectors for senses
        for (String sense : senses) {
            if (senseMap.containsKey(sense)) {
                usableSenses.add(sense);
            }
        }

        // If no senses good for disambiguation were found, try the upper-case version
        if (usableSenses.size() == 0 && acronymExpansionsModel.hasExpansions(acronym.toUpperCase())) {
            for (String sense : senses) {
                if (senseMap.containsKey(sense)) {
                    usableSenses.add(sense);
                }
            }
        }

        // Should this just guess the first sense instead?
        if (usableSenses.size() == 0) {
            return Acronyms.UNKNOWN;
        }

        double best = -Double.MAX_VALUE;
        String winner = Acronyms.UNKNOWN;

        SparseVector vector = wordVectorSpace.vectorize(context, forThisIndex);

        // Loop through all possible senses for this acronym
        for (String sense : usableSenses) {
            SparseVector compVec = senseMap.get(sense);
            double score = vector.dot(compVec);
            if (score > best) {
                best = score;
                winner = sense;
            }
        }
        return winner;
    }

    /**
     * Remove a single word from the model
     *
     * @param word the word to remove
     */
    public void removeWord(String word) {
        Integer ind = wordVectorSpace.removeWord(word);
        if (ind != null) {
            for (SparseVector vec : senseMap.values()) {
                vec.set(ind, 0);
            }
        }
    }

    /**
     * Remove all words from the model except those given
     *
     * @param wordsToRemove the set of words to keep
     */
    public void removeWordsExcept(Set<String> wordsToRemove) {
        Set<Integer> removed = wordVectorSpace.removeWordsExcept(wordsToRemove);
        removed.remove(null);
        for (SparseVector vec : senseMap.values()) {
            for (Integer ind : removed) {
                if (ind != null) {
                    vec.set(ind, 0);
                }
            }
        }
    }

    void writeToDirectory(Path outputDir) throws IOException {
        Yaml yaml = YamlSerialization.createYaml();

        yaml.dump(alignmentModel, Files.newBufferedWriter(outputDir.resolve("alignment.yml")));
        yaml.dump(wordVectorSpace, Files.newBufferedWriter(outputDir.resolve("vectorSpace.yml")));
        serializeSenseMap(outputDir.resolve("senseMap.ser"));
    }

    /**
     * Write senseMap to a file. Uses a mix of Object serialization and binary data.
     * @param outFile output file to serialize to
     * @throws IOException
     */
    private void serializeSenseMap(Path outFile) throws IOException {
        ObjectOutputStream stream = new ObjectOutputStream(new FileOutputStream(outFile.toFile()));
        String[] words = senseMap.keySet().toArray(new String[0]);
        stream.writeObject(words);
        for (String word : words) {
            SparseVector vec = senseMap.get(word);
            Map<Integer, Double> vecMap = vec.getVector();
            int size = vecMap.size();
            stream.write(ByteBuffer.allocate(4).putInt(size).array());
            ByteBuffer buf = ByteBuffer.allocate(size*8);
            for (Map.Entry<Integer, Double> e : vecMap.entrySet()) {
                buf.putInt(e.getKey());
                buf.putFloat((float)(double)e.getValue());
            }
            stream.write(buf.array());
        }
        stream.close();
    }

    /**
     * Load senseMap from a file. File must have been generated by the serializeSenseMap method in this class.
     * @param senseMapPath path to the serialized senseMap
     * @return a map between senses and their vectors
     * @throws IOException
     */
    private static Map<String, SparseVector> deserializeSenseMap(Path senseMapPath) throws IOException {
        ObjectInputStream stream = new ObjectInputStream(new FileInputStream(senseMapPath.toFile()));
        ByteBuffer intByteBuffer = ByteBuffer.allocate(4);
        Map<String, SparseVector> senseMap;
        String[] words;
        try {
            words = (String[]) stream.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException();
        }
        senseMap = new HashMap<>(words.length);
        for (String word : words) {
            stream.readFully(intByteBuffer.array());
            int size = intByteBuffer.getInt(0);
            Map<Integer, Double> vector = new HashMap<>(size);
            ByteBuffer buf = ByteBuffer.allocate(size*8);
            stream.readFully(buf.array());
            for (int i = 0; i < size; i++) {
                int index = buf.getInt();
                float val = buf.getFloat();
                vector.put(index, (double) val);
            }
            senseMap.put(word, new SparseVector(vector));
        }
        stream.close();
        return senseMap;
    }

    /**
     *
     */
    @Singleton
    static class Loader extends DataLoader<AcronymVectorModel> {
        private final Provider<AlignmentModel> alignmentModel;

        private final Path vectorSpacePath;

        private final Path senseMapPath;

        private final boolean useAlignment;

        private final AcronymExpansionsModel expansionsModel;

        @Inject
        public Loader(Provider<AlignmentModel> alignmentModel,
                      @Setting("acronym.useAlignment") Boolean useAlignment,
                      @Setting("acronym.vector.model.path") Path vectorSpacePath,
                      @Setting("acronym.senseMap.path") Path senseMapPath,
                      AcronymExpansionsModel expansionsModel) {
            this.alignmentModel = alignmentModel;
            this.useAlignment = useAlignment;
            this.vectorSpacePath = vectorSpacePath;
            this.senseMapPath = senseMapPath;
            this.expansionsModel = expansionsModel;
        }

        @Override
        protected AcronymVectorModel loadModel() throws BiomedicusException {

            Yaml yaml = YamlSerialization.createYaml();

            try {
                LOGGER.info("Loading acronym vector space: {}", vectorSpacePath);
                @SuppressWarnings("unchecked")
                WordVectorSpace wordVectorSpace = (WordVectorSpace) yaml.load(Files.newBufferedReader(vectorSpacePath));

                LOGGER.info("Loading acronym sense map: {}", senseMapPath);
                @SuppressWarnings("unchecked")
                Map<String, SparseVector> senseMap = deserializeSenseMap(senseMapPath);

                return new AcronymVectorModel(wordVectorSpace, senseMap, expansionsModel, useAlignment ? alignmentModel.get() : null);
            } catch (IOException e) {
                throw new BiomedicusException(e);
            }
        }
    }
}
